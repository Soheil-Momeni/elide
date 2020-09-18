/*
 * Copyright 2020, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.async.service;

import com.yahoo.elide.Elide;
import com.yahoo.elide.ElideResponse;
import com.yahoo.elide.async.models.AsyncQuery;
import com.yahoo.elide.async.models.AsyncQueryResult;
import com.yahoo.elide.async.models.QueryType;
import com.yahoo.elide.async.models.ResultFormatType;
import com.yahoo.elide.async.models.ResultType;
import com.yahoo.elide.core.DataStoreTransaction;
import com.yahoo.elide.core.PersistentResource;
import com.yahoo.elide.core.exceptions.InvalidValueException;
import com.yahoo.elide.graphql.GraphQLQuery;
import com.yahoo.elide.graphql.GraphQLRequestScope;
import com.yahoo.elide.graphql.QueryRunner;
import com.yahoo.elide.graphql.parser.GraphQLEntityProjectionMaker;
import com.yahoo.elide.graphql.parser.GraphQLProjectionInfo;
import com.yahoo.elide.request.EntityProjection;
import com.yahoo.elide.security.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.opendevl.JFlat;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import org.apache.http.NameValuePair;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.utils.URIBuilder;

import io.reactivex.Observable;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;

/**
 * Callable thread for executing the query provided in Async Query.
 * It will also update the query status and result object at different
 * stages of execution.
 */
@Slf4j
@Data
public class AsyncQueryThread implements Callable<AsyncQueryResult> {

    private AsyncQuery queryObj;
    private AsyncQueryResult queryResultObj;
    private User user;
    private Elide elide;
    private final QueryRunner runner;
    private AsyncQueryDAO asyncQueryDao;
    private String apiVersion;
    private ResultStorageEngine resultStorageEngine;
    private Integer downloadRecordCount = 0;
    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public AsyncQueryResult call() throws URISyntaxException, IOException {
         return processQuery();
    }

    public AsyncQueryThread(AsyncQuery queryObj, User user, Elide elide, QueryRunner runner,
            AsyncQueryDAO asyncQueryDao, String apiVersion, ResultStorageEngine resultStorageEngine) {
        this.queryObj = queryObj;
        this.user = user;
        this.elide = elide;
        this.runner = runner;
        this.asyncQueryDao = asyncQueryDao;
        this.apiVersion = apiVersion;
        this.resultStorageEngine = resultStorageEngine;
    }

   /**
    * This is the main method which processes the Async Query request, executes the query and updates
    * values for AsyncQuery and AsyncQueryResult models accordingly.
    * @return AsyncQueryResult AsyncQueryResult
    * @throws URISyntaxException URISyntaxException
    * @throws IOException IOException
    */
    protected AsyncQueryResult processQuery() throws URISyntaxException, IOException {
        UUID requestId = UUID.fromString(queryObj.getRequestId());

        ElideResponse response = null;
        String responseBody = null;
        boolean isError = false;
        QueryType queryType = queryObj.getQueryType();
        boolean isDownload = queryObj.getResultType() == ResultType.DOWNLOAD;

        if (isDownload && resultStorageEngine == null) {
            throw new IllegalStateException("ResultStorageEngine unavailable.");
        }

        log.debug("AsyncQuery Object from request: {}", queryObj);

        // Create AsyncQueryResult entry for AsyncQuery
        queryResultObj = new AsyncQueryResult();

        if (isDownload) {
            Observable<String> downloadString = Observable.empty();
            if (queryType.equals(QueryType.JSONAPI_V1_0)) {
                // TODO: executeDownloadRequest for JSON API
                throw new InvalidValueException("Download not supported for JSON API Query");
            } else if (queryType.equals(QueryType.GRAPHQL_V1_0)) {
                GraphQLQuery graphQLQuery = createGraphQLQuery();
                Observable<PersistentResource> observableResults = executeDownloadRequest(graphQLQuery, requestId);
                downloadString = processObservablePersistentResource(observableResults);
            }

            queryResultObj.setHttpStatus(200);
            queryResultObj.setContentLength(null);

            storeResults(queryObj, downloadString);

            queryResultObj.setRecordCount(this.downloadRecordCount);
            queryResultObj.setResponseBody("URL to be generated");
        } else {
            if (queryType.equals(QueryType.JSONAPI_V1_0)) {
                response = executeJsonApiRequest(queryObj, requestId);
            } else if (queryType.equals(QueryType.GRAPHQL_V1_0)) {
                GraphQLQuery graphQLQuery = createGraphQLQuery();
                response = executeGraphqlRequest(graphQLQuery, requestId);
            }
            nullResponseCheck(response);
            responseBody = response.getBody();
            isError = checkJsonStrErrorMessage(responseBody);

            queryResultObj.setHttpStatus(response.getResponseCode());
            queryResultObj.setContentLength(responseBody.length());
            queryResultObj.setResponseBody(responseBody);

            if (isError) {
                return queryResultObj;
            }

            calculateRecordCount(response, queryType);
            queryResultObj.setRecordCount(this.downloadRecordCount);
        }

        queryResultObj.setCompletedOn(new Date());

        return queryResultObj;
    }

    /**
     * This method calculates the number of records from the response.
     * @param response is the ElideResponse
     * @param queryType is the query type (GraphQL or JSON).
     * @throws IOException Exception thrown by JsonPath
     */
    protected void calculateRecordCount(ElideResponse response, QueryType queryType)
            throws IOException {
        Integer count = null;
        if (response.getResponseCode() == 200) {
            if (queryType.equals(QueryType.GRAPHQL_V1_0)) {
                List<Integer> countList = JsonPath.read(response.getBody(), "$..edges.length()");
                count = countList.size() > 0 ? countList.get(0) : 0;
            } else if (queryType.equals(QueryType.JSONAPI_V1_0)) {
                count = JsonPath.read(response.getBody(), "$.data.length()");
            }
        }
        this.downloadRecordCount = count;
    }

    /**
     * This method stores the results.
     * @param asyncQuery is the async query object.
     * @param result is the observable result to store.
     * @return AsyncQuery object
     */
    protected AsyncQuery storeResults(AsyncQuery asyncQuery, Observable<String> result) {
        return resultStorageEngine.storeResults(asyncQuery, result);
    }

    /**
     * This method checks if the json string is error message.
     * @param jsonStr is the response.getBody() we get from the response
     * @return is the message an error message
     */
    protected boolean checkJsonStrErrorMessage(String jsonStr) {
        boolean isError = false;
        try {
            isError = (Integer) JsonPath.read(jsonStr, "$.errors.length()") >= 1;
        } catch (PathNotFoundException e) {
            //ignore when not an error message
            log.trace(e.getMessage());
        }
        return isError;
    }

    /**
     * Process Observable of Persistent Resource to generate the Observable of download-ready result string.
     * @param resources Observable Persistent Resource.
     * @return result as Observable of String
     */
    protected Observable<String> processObservablePersistentResource(Observable<PersistentResource> resources) {
        Observable<String> results = Observable.just("No Records Generated");

        if (queryObj.getResultFormatType() == ResultFormatType.CSV) {
            results =  resources.map(resource -> convertToCSV(resource));
        } else if (queryObj.getResultFormatType() == ResultFormatType.JSON) {
            results = resources.map(resource -> resourceToJsonStr(resource));
        }

        return results;
    }

    private String resourceToJsonStr(PersistentResource resource) throws IOException {
        return mapper.writeValueAsString(resource.getObject());
    }

    /**
     * This method converts the JSON response to a CSV response type.
     * @param resource is the Persistent Resource to convert
     * @return returns string which is in CSV format
     * @throws IllegalStateException Exception thrown
     */
    protected String convertToCSV(PersistentResource resource) {
        if (resource == null) {
            return null;
        }

        StringBuilder str = new StringBuilder();

        List<Object[]> json2csv;

        try {
            String jsonStr = resourceToJsonStr(resource);

            JFlat flatMe = new JFlat(jsonStr);

            json2csv = flatMe.json2Sheet().headerSeparator("_").getJsonAsSheet();

            int index = 0;

            for (Object[] obj : json2csv) {
                // Skip Header record from 2nd time onwards.
                if (index++ == 0 && downloadRecordCount != 0) {
                    continue;
                }

                String objString = Arrays.toString(obj);
                if (objString != null) {
                    objString = objString.substring(1, objString.length() - 1);
                }
                str.append(objString);
                // Only append new lines after header.
                if (index == 1) {
                    str.append(System.getProperty("line.separator"));
                }

                incrementRecordCount();
            }
        } catch (Exception e) {
            log.debug("Exception while converting to CSV: {}", e.getMessage());
            throw new IllegalStateException(e);
        }
        return str.toString();
    }

    /**
     * Increment downloadable record count.
     */
    protected void incrementRecordCount() {
        this.downloadRecordCount++;
    }

    /**
     * This method parses the url and gets the query params.
     * And adds them into a MultivaluedMap to be used by underlying Elide.get method
     * @param query query from the Async request
     * @throws URISyntaxException URISyntaxException from malformed or incorrect URI
     * @return MultivaluedMap with query parameters
     */
    protected MultivaluedMap<String, String> getQueryParams(String query) throws URISyntaxException {
        URIBuilder uri;
        uri = new URIBuilder(query);
        MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<String, String>();
        for (NameValuePair queryParam : uri.getQueryParams()) {
            queryParams.add(queryParam.getName(), queryParam.getValue());
        }
        return queryParams;
    }

    /**
     * This method parses the url and gets the query params.
     * And retrieves path to be used by underlying Elide.get method
     * @param query query from the Async request
     * @throws URISyntaxException URISyntaxException from malformed or incorrect URI
     * @return Path extracted from URI
     */
    protected String getPath(String query) throws URISyntaxException {
        URIBuilder uri;
        uri = new URIBuilder(query);
        return uri.getPath();
    }

    private ElideResponse executeJsonApiRequest(AsyncQuery queryObj, UUID requestId) throws URISyntaxException {
        MultivaluedMap<String, String> queryParams = getQueryParams(queryObj.getQuery());
        log.debug("Extracted QueryParams from AsyncQuery Object: {}", queryParams);

        //TODO - we need to add the baseUrlEndpoint to the queryObject.
        ElideResponse response = elide.get("", getPath(queryObj.getQuery()), queryParams, user, apiVersion, requestId);
        log.debug("JSONAPI_V1_0 getResponseCode: {}, JSONAPI_V1_0 getBody: {}",
                response.getResponseCode(), response.getBody());
        return response;
    }

    private String extractAndValidateQuery(GraphQLQuery graphQLQuery) {
        if (graphQLQuery.getNode().isArray()) {
            throw new InvalidValueException(graphQLQuery.getGraphQLDocument() + " is an array.",
                    (Throwable) null);
        }

        String executableQuery = graphQLQuery.getQuery();
        if (executableQuery == null || graphQLQuery.isMutation()) {
            throw new InvalidValueException(graphQLQuery.getGraphQLDocument() + " is missing `query` key or"
                    + " is a mutation.", (Throwable) null);
        }

        return executableQuery;
    }

    private ElideResponse executeGraphqlRequest(GraphQLQuery graphQLQuery, UUID requestId) throws URISyntaxException {
        //TODO - we need to add the baseUrlEndpoint to the queryObject.
        ElideResponse response = runner.run("", graphQLQuery, user, requestId);
        log.debug("GRAPHQL_V1_0 getResponseCode: {}, GRAPHQL_V1_0 getBody: {}",
                response.getResponseCode(), response.getBody());
        return response;
    }

    private GraphQLQuery createGraphQLQuery() throws IOException {
        String graphQLDocument = queryObj.getQuery();
        JsonNode node = QueryRunner.getTopLevelNode(graphQLDocument);
        GraphQLQuery graphQLQuery = new GraphQLQuery(graphQLDocument, node);
        extractAndValidateQuery(graphQLQuery);
        return graphQLQuery;
    }

    private Observable<PersistentResource> executeDownloadRequest(GraphQLQuery graphQLQuery, UUID requestId)
            throws IOException {
        Observable<PersistentResource> results = Observable.empty();
        try (DataStoreTransaction tx = elide.getDataStore().beginTransaction()) {
            elide.getTransactionRegistry().addRunningTransaction(requestId, tx);
            GraphQLProjectionInfo projectionInfo = new GraphQLEntityProjectionMaker(elide.getElideSettings(),
                    graphQLQuery.getVariables(), apiVersion).make(graphQLQuery.getQuery());

            //TODO - we need to add the baseUrlEndpoint to the queryObject.
            GraphQLRequestScope requestScope =
                    new GraphQLRequestScope("", tx, user, apiVersion,
                            elide.getElideSettings(), projectionInfo, requestId);

            // TODO - Confirm if this is possible, valid and needed.
            if (projectionInfo.getProjections().size() > 1) {
                throw new IllegalStateException("More than 1 projections in the query");
            }

            // TODO - Check if the Entity is an Aggregation Annotated Model
            Optional<Entry<String, EntityProjection>> optionalEntry =
                    projectionInfo.getProjections().entrySet().stream().findFirst();
            if (optionalEntry.isPresent()) {
                results = PersistentResource.loadRecords(optionalEntry.get().getValue(), Collections.emptyList(),
                        requestScope);
            }
            // Hibernate does not like if session has not been flushed/committed before close. Throws IOException.
            tx.flush(requestScope);
            tx.commit(requestScope);
        } finally {
            elide.getTransactionRegistry().removeRunningTransaction(requestId);
        }
        return results;
    }

    private void nullResponseCheck(ElideResponse response) throws NoHttpResponseException {
        if (response == null) {
            throw new NoHttpResponseException("Response for request returned as null");
        }
    }
}
