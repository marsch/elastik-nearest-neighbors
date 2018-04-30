package org.elasticsearch.plugin.ann;

import org.apache.lucene.search.Query;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.reindex.ScrollableHitSource;
import org.elasticsearch.rest.*;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.*;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

public class AnnRestAction extends BaseRestHandler {

    public static String NAME = "_search_ann";
    private final String HASHES_KEY = "hashes";
    private final Integer K1_DEFAULT = 99;     // Number of documents returned based on hashes only.
    private final Integer K2_DEFAULT = 10;     // Number of documents returned based on exact KNN.

    @Inject
    public AnnRestAction(Settings settings, RestController controller) {
        super(settings);
        controller.registerHandler(GET, "/{index}/{type}/{id}/" + NAME, this);
    }

    @Override
    public String getName() {
        return NAME;
    }

    public static Double euclideanDistance(List<Double> A, List<Double> B) {
        Double squaredDistance = 0.;
        for (Integer i = 0; i < A.size(); i++)
            squaredDistance += Math.pow(A.get(i) - B.get(i), 2);
        return Math.sqrt(squaredDistance);
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {

        // TODO: the search request should be modified such that regular query options (e.g. _source: ["description"])
        // can be included. See Carrot2 examples and docs: https://github.com/carrot2/elasticsearch-carrot2/blob/master/doc/

        final Double nanosecondsInSecond = 1000000000.0;
        Long timestamp = 0L;
        List<Tuple<String, Double>> timing = new ArrayList<>();

        // Parse request parameters.
        final String index = restRequest.param("index");
        final String type = restRequest.param("type");
        final String id = restRequest.param("id");
        final Integer k1 = Integer.parseInt(restRequest.param("k1", K1_DEFAULT.toString()));
        final Integer k2 = Integer.parseInt(restRequest.param("k2", K2_DEFAULT.toString()));

        // Retrieve the document specified by index/type/id.
        timestamp = System.nanoTime();
        GetResponse baseGetResponse = client.prepareGet(index, type, id).get();
        Map<String, Object> baseSource = baseGetResponse.getSource();

        @SuppressWarnings("unchecked")
        Map<String, Integer> baseHashes = (Map<String, Integer>) baseSource.get(HASHES_KEY);

        @SuppressWarnings("unchecked")
        List<Double> baseVector = (List<Double>) baseSource.get("vector");
        timing.add(Tuple.tuple("Retrieving base document", (System.nanoTime() - timestamp) / nanosecondsInSecond));

        // Retrieve the documents with most matching hashes. https://stackoverflow.com/questions/10773581
        timestamp = System.nanoTime();
        QueryBuilder queryBuilder = QueryBuilders.boolQuery();
        for (Map.Entry<String, Integer> entry : baseHashes.entrySet()) {
            // TODO: using String.format() gives a forbidden APIs waring here.
            // String termKey = String.format("hashes.%s", entry.getKey());
            String termKey = HASHES_KEY + "." + entry.getKey();
            ((BoolQueryBuilder) queryBuilder).should(QueryBuilders.termQuery(termKey, entry.getValue()));
        }
        timing.add(Tuple.tuple("Building approximate query", (System.nanoTime() - timestamp) / nanosecondsInSecond));

        timestamp = System.nanoTime();
        SearchResponse approximateSearchResponse = client
                .prepareSearch(index)
                .setTypes(type)
                // .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(queryBuilder)
                .setSize(k1)
                .setExplain(false)
                .get();
        timing.add(Tuple.tuple("Executing approximate query", (System.nanoTime() - timestamp) / nanosecondsInSecond));

        // Compute exact KNN on the approximate neighbors.
        timestamp = System.nanoTime();
        SearchHits searchHits = approximateSearchResponse.getHits();
        List<Tuple<String, Double>> idsAndDistances = new ArrayList<>();
        for (SearchHit hit : searchHits) {
            Map<String, Object> hitSource = hit.getSourceAsMap();
            @SuppressWarnings("unchecked")
            List<Double> hitVector = (List<Double>) hitSource.get("vector");
            idsAndDistances.add(Tuple.tuple(hit.getId(), euclideanDistance(baseVector, hitVector)));
        }
        timing.add(Tuple.tuple("Computing distances", (System.nanoTime() - timestamp) / nanosecondsInSecond));

        // Sort ids by the exact distance in ascending order.
        timestamp = System.nanoTime();
        idsAndDistances.sort(Comparator.comparing(Tuple::v2));
        List<Tuple<String, Double>> idsAndDistancesTopK = idsAndDistances.subList(0, k2);
        timing.add(Tuple.tuple("Sorting by distance", (System.nanoTime() - timestamp) / nanosecondsInSecond));

        return channel -> {
            XContentBuilder builder = channel.newBuilder();
            builder.startObject();
            builder.field("nearest_neighbors", idsAndDistancesTopK);
            builder.field("timing", timing);
            builder.endObject();
            channel.sendResponse(new BytesRestResponse(RestStatus.OK, builder));
        };
    }


}
