/*
 * Generated by Robotoworks Mechanoid
 */
package eu.masconsult.template.recipes.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.ContentProviderOperation;
import android.content.OperationApplicationException;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.robotoworks.mechanoid.Mechanoid;
import com.robotoworks.mechanoid.db.MechanoidContentProvider;
import com.robotoworks.mechanoid.db.SQuery;
import com.robotoworks.mechanoid.db.SQuery.Op;
import com.robotoworks.mechanoid.net.Response;
import com.robotoworks.mechanoid.net.ServiceException;
import com.robotoworks.mechanoid.ops.OperationContext;
import com.robotoworks.mechanoid.ops.OperationResult;

import eu.masconsult.template.recipes.Constants;
import eu.masconsult.template.recipes.content.RecipesDBContract;
import eu.masconsult.template.recipes.content.RecipesDBContract.Ingredients;
import eu.masconsult.template.recipes.content.RecipesDBContract.Recipes;
import eu.masconsult.template.recipes.content.RecipesDBContract.Recipes.Builder;
import eu.masconsult.template.recipes.content.RecipesRecord;
import eu.masconsult.template.recipes.net.CustomGetRecipesResult;
import eu.masconsult.template.recipes.net.GetRecipesRequest;
import eu.masconsult.template.recipes.net.GetRecipesResult;
import eu.masconsult.template.recipes.net.Recipe;
import eu.masconsult.template.recipes.net.RecipesNetClient;
import eu.masconsult.template.recipes.prefs.RecipesPreferences;

public class ImportRecipesOperation extends AbstractImportRecipesOperation {

	private static final String TAG = ImportRecipesOperation.class.getSimpleName();

	private static final int MAX_ROWS_PER_BATCH_OPERATION = 250;

	@Override
	protected OperationResult onExecute(OperationContext context, Args args) {
		Log.d(TAG, "Importing recipes");

		boolean failed = false;
		Exception exception;
		try {
			readRecipes(context, args.file, args.local);

			RecipesPreferences.getInstance().updateLastSucceedUpdateUrl(args.file);

			return OperationResult.ok();
		} catch (Exception e) {
			Log.e(TAG, "Error while importing recipes. ", e);
			failed = true;
			exception = e;
		}

		if (failed
				&& !TextUtils.isEmpty(RecipesPreferences.getInstance().getLastSucceedUpdateUrl())) {
			Log.d(TAG, "Try with last successfull updateUrl.");

			try {
				readRecipes(context, RecipesPreferences.getInstance().getLastSucceedUpdateUrl(),
						false);

				return OperationResult.ok();
			} catch (Exception e) {
				Log.e(TAG, "Error while importing recipes. ", e);
				e.printStackTrace();
				return OperationResult.error(e);
			}
		} else {
			return OperationResult.error(exception);
		}
	}

	private void readRecipes(OperationContext context, String file, boolean isLocal)
			throws IOException, ServiceException, RemoteException, OperationApplicationException {
		List<Recipe> recipes;
		GetRecipesResult result;
		String nextPageUrl = file;
		boolean firstIteration = true;

		do {
			Log.d(TAG, String.format("Getting recipes from: %s", nextPageUrl));

			RecipesNetClient client = new RecipesNetClient(nextPageUrl);

			if (isLocal && firstIteration) {
				result = new CustomGetRecipesResult(client.getReaderProvider(), context
						.getApplicationContext().getAssets().open(nextPageUrl));
				recipes = result.getRecipes();
			} else {
				Response<GetRecipesResult> response = client.getRecipes(new GetRecipesRequest());
				response.checkResponseCodeOk();
				result = response.parse();
			}

			recipes = result.getRecipes();

			insertRecipes(recipes);

			nextPageUrl = result.getNextPage();
			firstIteration = false;
		} while (!TextUtils.isEmpty(nextPageUrl));

		if (!TextUtils.isEmpty(result.getNextUpdate())) {
			RecipesPreferences.getInstance().updateNextUpdateUrl(result.getNextUpdate());
		}
	}

	private void insertRecipes(List<Recipe> recipes) throws RemoteException,
			OperationApplicationException {
		if (recipes != null) {
			Log.d(TAG, String.format("Found %d recipes", recipes.size()));
			Map<String, String> valuesMap = new HashMap<String, String>();
			valuesMap.put(Recipe.KEY_ID, Recipes.RECIPE_ID);

			final long startTime = System.currentTimeMillis();
			ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();

			int currentRecipeOperationIndex;

			int insertCount = 0;
			int updateCount = 0;

			for (Recipe recipe : recipes) {
				if (TextUtils.isEmpty(recipe.getId())) {
					recipe.setId(recipe.getCategory() + ":" + recipe.getName());
				}

				Builder recipeBuilder = Recipes.newBuilder();
				recipeBuilder.getValues().putAll(recipe.toContentValues(valuesMap));
				recipeBuilder.getValues().remove(Recipe.KEY_INGREDIENTS);
				recipeBuilder.getValues().put(Recipes.IMAGE, normalizeImageUrl(recipe.getImage()));

				if (recipe.getTotalTime() == 0) {
					recipeBuilder.getValues().put(Recipes.TOTAL_TIME,
							recipe.getPrepTime() + recipe.getCookTime());
				}

				RecipesRecord record = SQuery.newQuery()
						.expr(Recipes.RECIPE_ID, Op.EQ, recipe.getId())
						.selectFirst(Recipes.CONTENT_URI);

				ContentProviderOperation op;
				if (record != null) {
					op = ContentProviderOperation
							.newUpdate(
									Recipes.CONTENT_URI
											.buildUpon()
											.appendQueryParameter(
													MechanoidContentProvider.PARAM_NOTIFY,
													String.valueOf(false)).build())
							.withSelection(Recipes._ID + "=?", new String[] { record.getId() + "" })
							.withValues(recipeBuilder.getValues()).build();
					operations.add(op);
					operations.add(ContentProviderOperation
							.newDelete(Ingredients.CONTENT_URI)
							.withSelection(Ingredients.RECIPE_ID + "=?",
									new String[] { record.getId() + "" }).build());
					updateCount++;
				} else {
					op = ContentProviderOperation
							.newInsert(
									Recipes.CONTENT_URI
											.buildUpon()
											.appendQueryParameter(
													MechanoidContentProvider.PARAM_NOTIFY,
													String.valueOf(false)).build())
							.withValues(recipeBuilder.getValues()).build();
					insertCount++;
				}

				operations.add(op);
				currentRecipeOperationIndex = operations.indexOf(op);

				for (String ingredient : recipe.getIngredients()) {
					Ingredients.Builder ingredientBuilder = Ingredients.newBuilder();
					ingredientBuilder.setIngredient(ingredient);
					ingredientBuilder.setRecipeId(0);

					ContentProviderOperation.Builder ingredientOperationBuilder = ContentProviderOperation
							.newInsert(
									Ingredients.CONTENT_URI
											.buildUpon()
											.appendQueryParameter(
													MechanoidContentProvider.PARAM_NOTIFY,
													String.valueOf(false)).build()).withValues(
									ingredientBuilder.getValues());

					if (record != null) {
						ingredientOperationBuilder.withValue(Ingredients.RECIPE_ID, record.getId());
					} else {
						ingredientOperationBuilder.withValueBackReference(Ingredients.RECIPE_ID,
								currentRecipeOperationIndex);
					}

					operations.add(ingredientOperationBuilder.build());
				}

				if (operations.size() >= MAX_ROWS_PER_BATCH_OPERATION) {
					Log.d(TAG, "saving list");
					Mechanoid.getContentResolver().applyBatch(RecipesDBContract.CONTENT_AUTHORITY,
							operations);

					Log.d(TAG, "Clear operations list");
					operations.clear();
				}
			}

			if (operations.size() > 0) {
				Mechanoid.getContentResolver().applyBatch(RecipesDBContract.CONTENT_AUTHORITY,
						operations);
			}

			final long time = (System.currentTimeMillis() - startTime) / 1000;
			Log.d(TAG, String.format(
					"Inserted %d recipes and updated %d recipes in db for %d seconds", insertCount,
					updateCount, time));
		} else {
			Log.d(TAG, "No recipes found");
		}
	}

	private String normalizeImageUrl(String url) {
		if (TextUtils.isEmpty(url)) {
			return null;
		}
		int idx = url.indexOf("://");
		if (idx != -1) {
			return url;
		}
		return "assets://" + Constants.IMAGES_DIRECTORY_NAME + url;
	}
}
