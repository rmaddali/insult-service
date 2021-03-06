package com.redhat.qcon.insult.services.insult;

import com.redhat.qcon.kafka.services.reactivex.KafkaService;
import io.reactivex.Maybe;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.circuitbreaker.CircuitBreaker;
import io.vertx.reactivex.core.CompositeFuture;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;

import static java.lang.String.format;

public class InsultServiceImpl implements InsultService {

    public static final int HTTP_CLIENT_TIMEOUT = 500;
    Vertx vertx;
    WebClient nounClient, adjClient;
    KafkaService kafka;
    CircuitBreaker adjBreaker;
    CircuitBreaker nounBreaker;

    /**
     * Default constructor
     * @param vertx The Vert.x instance to be used
     * @param config The {@link JsonObject} configuration for this service
     */
    public InsultServiceImpl(io.vertx.core.Vertx vertx, JsonObject config) {

        kafka = KafkaService.createProxy(Vertx.newInstance(vertx), "kafka.service");

        JsonObject nounConfig = config.getJsonObject("noun");
        JsonObject adjConfig = config.getJsonObject("adjective");
        this.vertx = Vertx.newInstance(vertx);
        WebClientOptions nounClientOpts = new WebClientOptions(nounConfig)
                .setLogActivity(false);
        WebClientOptions adjClientOpts = new WebClientOptions(adjConfig)
                .setLogActivity(false);
        nounClient = WebClient.create(this.vertx, nounClientOpts);
        adjClient = WebClient.create(this.vertx, adjClientOpts);

        CircuitBreakerOptions breakerOpts = new CircuitBreakerOptions()
                                                    .setFallbackOnFailure(true)
                                                    .setMaxFailures(3)
                                                    .setMaxRetries(3)
                                                    .setResetTimeout(15000)
                                                    .setTimeout(250);

        adjBreaker = CircuitBreaker
                        .create("adjBreaker", Vertx.newInstance(vertx), breakerOpts)
                        .openHandler(t -> new JsonObject().put("adj", "[open]"))
                        .fallback(t -> new JsonObject().put("adj", "[failure]"))
                        .reset();

        nounBreaker = CircuitBreaker
                        .create("nounBreaker", Vertx.newInstance(vertx), breakerOpts)
                        .openHandler(t -> new JsonObject().put("noun", "[open]"))
                        .fallback(t -> new JsonObject().put("noun", "[failure]"))
                        .reset();
    }

    /**
     * Request adjectives and a noun from the other microservices
     * @param insultGetHandler A {@link Handler} callback for the results
     */
    @Override
    public void getREST(Handler<AsyncResult<JsonObject>> insultGetHandler) {
        // Request 2 adjectives and a noun in parallel, then handle the results
        CompositeFuture.all(getNoun(), getAdjective(), getAdjective())
                .rxSetHandler()
                .flatMapMaybe(InsultServiceImpl::mapResultToError)   // Map errors to an exception
                .map(InsultServiceImpl::buildInsult)        // Combine the 3 results into a single JSON object
                .onErrorReturn(Future::failedFuture)        // When an exception happens, map it to a failed future
                .subscribe(insultGetHandler::handle);       // Map successful JSON to a succeeded future
    }

    /**
     * Take results of {@link CompositeFuture} and return a composed {@link JsonObject} containing the insult components
     * @param cf An instance of {@link CompositeFuture} which MUST be succeeded, otherwise it would have been filtered
     * @return A {@link JsonObject} containing a noun and an array of adjectives.
     */
    private static AsyncResult<JsonObject> buildInsult(CompositeFuture cf) {
        JsonObject insult = new JsonObject();
        JsonArray adjectives = new JsonArray();

        // Because there is no garanteed order of the returned futures, we need to parse the results
        for (int i=0; i<cf.size(); i++) {
            JsonObject item = cf.resultAt(i);
            if (item.containsKey("adj")) {
                adjectives.add(item.getString("adj"));
            } else {
                insult.put("noun", item.getString("noun"));
            }
        }
        insult.put("adj1", adjectives.getString(0));
        insult.put("adj2", adjectives.getString(1));

        return Future.succeededFuture(insult);
    }

    /**
     * Requests an adjective from the appropriate microservice and returns a future with the result
     * @return A {@link Future} of type {@link JsonObject} which will contain an adjective on success
     */
    io.vertx.reactivex.core.Future<JsonObject> getAdjective() {
        return adjBreaker.execute(fut ->
            adjClient.get("/api/v1/adjective")
                    .timeout(HTTP_CLIENT_TIMEOUT)
                    .rxSend()
                    .flatMapMaybe(InsultServiceImpl::mapStatusToError)
                    .map(HttpResponse::bodyAsJsonObject)
                    .subscribe(fut::complete, fut::fail));
    }

    /**
     * Requests a noun from the appropriate microservice and returns a future with the result
     * @return A {@link Future} of type {@link JsonObject} which will contain a noun on success
     */
    io.vertx.reactivex.core.Future<JsonObject> getNoun() {
        return nounBreaker.execute(fut ->
            nounClient.get("/api/v1/noun")
                    .timeout(HTTP_CLIENT_TIMEOUT)
                    .rxSend()
                    .flatMapMaybe(InsultServiceImpl::mapStatusToError)
                    .map(HttpResponse::bodyAsJsonObject)
                    .subscribe(fut::complete, fut::fail));
    }

    /**
     * Use the {@link KafkaService} event bus proxy to make calls to the Kafka microservice
     * @param insult An insult made up of 2 adjectives and a noun
     * @param handler A handler to be called
     */
    @Override
    public InsultService publish(JsonObject insult, Handler<AsyncResult<Void>> handler) {
        Future<Void> fut = Future.future();
        handler.handle(fut);
        kafka.rxPublish(insult)
                .toObservable()
                .doOnError(fut::fail)
                .subscribe(v -> fut.complete());
        return this;
    }

    /**
     * When the {@link CompositeFuture} is failed, throws an exception in order to interrups the RxJava stream processing
     * @param res The {@link CompositeFuture} to be processed
     * @return The same as the input if the {@link CompositeFuture} was succeeded
     */
    private static final Maybe<CompositeFuture> mapResultToError(CompositeFuture res) {
        if (res.succeeded()) {
            return Maybe.just(res);
        } else {
            return Maybe.error(res.cause());
        }

    }

    /**
     * Maps HTTP error status codes to exceptions to interrupt the RxJava stream
     * processing and trigger an error handler
     * @param r The {@link HttpResponse} to be checked
     * @return The same as the input if the response code is 2XX, otherwise an Exception
     */
    private static final Maybe<HttpResponse<Buffer>> mapStatusToError(HttpResponse<Buffer> r) {
        if (r.statusCode()>=400) {
            String errorMessage = format("%d: %s\n%s",
                    r.statusCode(),
                    r.statusMessage(),
                    r.bodyAsString());
            Exception clientException = new Exception(errorMessage);
            return Maybe.error(clientException);
        } else {
            return Maybe.just(r);
        }
    }

    /**
     * Check the health of this service
     * @param healthCheckHandler A {@link Handler} callback for the results
     */
    @Override
    public void check(Handler<AsyncResult<JsonObject>> healthCheckHandler) {
        JsonObject status = new JsonObject();

        String nounState = nounBreaker.state().name();
        String adjState = adjBreaker.state().name();
        status.put("noun", nounState)
                .put("adj", adjState);

        if (nounState.contentEquals("OPEN") || adjState.contentEquals("OPEN")) {
            status.put("status", "OK");
        } else {
            status.put("status", "DEGRADED");
        }
        healthCheckHandler.handle(Future.succeededFuture(status));
    }
}
