package com.invixo.ss.service.samples;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.invixo.ss.service.utils.IndexBatchOperation;
import com.invixo.ss.service.utils.IndexBatchOperationResult;
import com.invixo.ss.service.utils.IndexBatchResult;
import com.invixo.ss.service.utils.IndexDefinition;
import com.invixo.ss.service.utils.IndexField;
import com.invixo.ss.service.utils.IndexSearchOptions;
import com.invixo.ss.service.utils.IndexSearchResult;
import com.invixo.ss.service.utils.IndexSuggestOptions;
import com.invixo.ss.service.utils.IndexSuggestResult;
import com.invixo.ss.service.utils.SearchIndexClient;

public class SearchServiceSample
{
    private static final String SERVICE_NAME = "taxdata3";
    private static final String INDEX_NAME = "tax-poc-index";
    private static final String API_KEY = "63BCC4552225E345B44C488B0717AA5B";

    public static void main( String[] args ) {
        SearchIndexClient indexClient = new SearchIndexClient(SERVICE_NAME, INDEX_NAME, API_KEY);
        try {
            createIndex(indexClient, true);
            indexData(indexClient);
            Thread.sleep(1000); // wait a second to allow indexing to happen
            searchSimple(indexClient);
            searchAllFeatures(indexClient);
            lookup(indexClient);
            suggest(indexClient);
        } catch (Exception e) {
            System.err.println("Exception:" + e.getMessage());
        }
    }

    static void createIndex(SearchIndexClient indexClient, boolean deleteFirst) throws IOException {
        // Typical application initialization may create an index if it doesn't exist. Deleting an index
        // on initialization is a sample-only thing to do
        if (deleteFirst) {
            indexClient.delete();
        }

        // Indexes may be created via the management UI in portal.azure.com or via APIs. In addition to field
        // details index definitions include options for custom scoring, suggesters and more
        if (!indexClient.exists()) {
            IndexDefinition definition = new IndexDefinition();

            Collection<IndexField> fields = definition.getFields();
            fields.add(new IndexField("id", "Edm.String").setKey(true));
            fields.add(new IndexField("name", "Edm.String").setSearchable(true).setAnalyzer("en.lucene"));
            fields.add(new IndexField("category", "Collection(Edm.String)").setFilterable(true).setFacetable(true));
            fields.add(new IndexField("rating", "Edm.Int32").setFilterable(true).setFacetable(true));
            fields.add(new IndexField("created", "Edm.DateTimeOffset").setFilterable(true).setSortable(true).setFacetable(true));

            IndexDefinition.Suggester suggester = new IndexDefinition.Suggester();
            suggester.setName("sg");
            suggester.setSourceFields(new String[]{"name"});
            definition.getSuggesters().add(suggester);

            indexClient.create(definition);
        }
    }

    static void indexData(SearchIndexClient indexClient) throws IOException {
        // In this case we create sample data in-memory. Typically this will come from another database, file or
        // API and will be turned into objects with the desired shape for indexing
        ArrayList<IndexBatchOperation> operations = new ArrayList<IndexBatchOperation>();
        operations.add(IndexBatchOperation.upload(newDocument("1", "first name", 10, "aaa", "bbb")));
        operations.add(IndexBatchOperation.upload(newDocument("2", "second name", 11, "aaa", "ccc")));
        operations.add(IndexBatchOperation.upload(newDocument("3", "second second name", 12, "aaa", "eee")));
        operations.add(IndexBatchOperation.upload(newDocument("4", "third name", 13, "ddd", "eee")));
        operations.add(IndexBatchOperation.delete("id", "5"));

        // consider handling HttpRetryException and backoff (wait 30, 60, 90 seconds) and retry
        IndexBatchResult result = indexClient.indexBatch(operations);
        if (result.getHttpStatus() == 207) {
            // handle partial success, check individual operation status/error message
        }
        for (IndexBatchOperationResult r: result.getOperationResults()) {
            System.out.printf("Operation for id: %s, success: %s\n", r.getKey(), r.getStatus());
        }
    }

    static void searchSimple(SearchIndexClient indexClient) throws IOException {
        IndexSearchOptions options = new IndexSearchOptions();
        options.setIncludeCount(true);
        IndexSearchResult result = indexClient.search("name", options);
        System.out.printf("Found %s hits\n", result.getCount());
        for (IndexSearchResult.SearchHit hit: result.getHits()) {
            System.out.printf("\tid: %s, name: %s, score: %s\n",
                    hit.getDocument().get("id"), hit.getDocument().get("name"), hit.getScore());
        }
    }

    static void searchAllFeatures(SearchIndexClient indexClient) throws IOException {
        IndexSearchOptions options = new IndexSearchOptions();
        options.setIncludeCount(true);
        options.setFilter("rating lt 13 and category/any(c: c eq 'aaa')");
        options.setOrderby("created");
        options.setSelect("id,name,category,created");
        options.setSearchFields("name");
        options.setFacets(new String[] { "rating,values:11|13", "category", "created" });
        options.setHighlight("name");
        options.setHighlightPreTag("*pre*");
        options.setHighlightPostTag("*post*");
        options.setTop(10);
        options.setSkip(1);
        options.setRequireAllTerms(true);
        options.setMinimumCoverage(0.75);

        IndexSearchResult result = indexClient.search("second name", options);

        // list search hits
        System.out.printf("Found %s hits, coverage: %s\n", result.getCount(), result.getCoverage() == null ? "-" : result.getCoverage());
        for (IndexSearchResult.SearchHit hit: result.getHits()) {
            System.out.printf("\tid: %s, name: %s, score: %s\n",
                    hit.getDocument().get("id"), hit.getDocument().get("name"), hit.getScore());
        }

        // list facets
        for (String field: result.getFacets().keySet()) {
            System.out.println(field + ":");
            for (IndexSearchResult.FacetValue value: result.getFacets().get(field)) {
                if (value.getValue() != null) {
                    System.out.printf("\t%s: %s\n", value.getValue(), value.getCount());
                }
                else {
                    System.out.printf("\t%s-%s: %s\n",
                            value.getFrom() == null ? "min" : value.getFrom(),
                            value.getTo() == null ? "max" : value.getTo(),
                            value.getCount());
                }
            }
        }
    }

    static void lookup(SearchIndexClient indexClient) throws IOException, URISyntaxException {
        Map<String, Object> document = indexClient.lookup("2");
        System.out.println("Document lookup, key='2'");
        System.out.printf("\tname: %s\n", document.get("name"));
        System.out.printf("\tcreated: %s\n", document.get("created"));
        System.out.printf("\trating: %s\n", document.get("rating"));
    }

    static void suggest(SearchIndexClient indexClient) throws IOException {
        IndexSuggestOptions options = new IndexSuggestOptions();
        options.setFuzzy(true);
        IndexSuggestResult result = indexClient.suggest("secp", "sg", options);
        System.out.println("Suggest results, coverage: " + (result.getCoverage() == null ? "-" : result.getCoverage().toString()));
        for (IndexSuggestResult.SuggestHit hit: result.getHits()) {
            System.out.printf("\ttext: %s (id: %s)\n", hit.getText(), hit.getDocument().get("id"));
        }
    }

    static Map<String, Object> newDocument(String id, String name, int rating, String ... categories) {
        HashMap<String, Object> doc = new HashMap<String, Object>();
        doc.put("id", id);
        doc.put("name", name);
        doc.put("rating", rating);
        doc.put("category", (String[])categories);
        doc.put("created", new Date());
        return doc;
    }
}
