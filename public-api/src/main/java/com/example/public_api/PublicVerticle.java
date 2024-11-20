package com.example.public_api;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.auth.jwt.JWTAuth;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import io.vertx.reactivex.ext.web.client.predicate.ResponsePredicate;
import io.vertx.reactivex.ext.web.codec.BodyCodec;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import io.vertx.reactivex.ext.web.handler.CorsHandler;
import io.vertx.reactivex.ext.web.handler.JWTAuthHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class PublicVerticle extends AbstractVerticle {
  private static final Logger logger = LoggerFactory.getLogger(PublicVerticle.class);
  private static final int HTTP_PORT = 4000;
  private WebClient webClient;
  private JWTAuth jwtAuth;

  @Override
  public Completable rxStart() {
    Router router = Router.router(vertx);

    Set<String> allowedHeaders = new HashSet<>();
    allowedHeaders.add("x-requested-with");
    allowedHeaders.add("Access-Control-Allow-Origin");
    allowedHeaders.add("origin");
    allowedHeaders.add("Content-Type");
    allowedHeaders.add("accept");
    allowedHeaders.add("Authorization");

    Set<HttpMethod> allowedMethods = new HashSet<>();
    allowedMethods.add(HttpMethod.GET);
    allowedMethods.add(HttpMethod.POST);
    allowedMethods.add(HttpMethod.OPTIONS);
    allowedMethods.add(HttpMethod.PUT);

    router.route().handler(CorsHandler
      .create("*")
      .allowedHeaders(allowedHeaders)
      .allowedMethods(allowedMethods));

    String publicKey;
    String privateKey;
    try {
      publicKey = CryptoHelper.publicKey();
      privateKey = CryptoHelper.privateKey();
    } catch (IOException e) {
      return Completable.error(e);
    }

    jwtAuth = JWTAuth.create(vertx, new JWTAuthOptions()
      .addPubSecKey(new PubSecKeyOptions()
        .setAlgorithm("RS256")
        .setBuffer(publicKey))
      .addPubSecKey(new PubSecKeyOptions()
        .setAlgorithm("RS256")
        .setBuffer(privateKey)));

    BodyHandler bodyHandler = BodyHandler.create();
    router.post().handler(bodyHandler);
    router.put().handler(bodyHandler);

    String prefix = "/api/v1";
    JWTAuthHandler jwtHandler = JWTAuthHandler.create(jwtAuth);

    // Account
    router.post(prefix + "/register").handler(this::register);
    router.post(prefix + "/token").handler(this::token);

    // Profile
    router.get(prefix + "/:username").handler(jwtHandler).handler(this::checkUser).handler(this::fetchUser);
    router.put(prefix + "/:username").handler(jwtHandler).handler(this::checkUser).handler(this::updateUser);

    // Data
    router.get(prefix + "/:username/total").handler(jwtHandler).handler(this::checkUser).handler(this::totalSteps);
    router.get(prefix + "/:username/:year/:month").handler(jwtHandler).handler(this::checkUser).handler(this::monthlySteps);
    router.get(prefix + "/:username/:year/:month/:day").handler(jwtHandler).handler(this::checkUser).handler(this::dailySteps);

    router.errorHandler(400, ctx -> {
      Throwable failure = ctx.failure();

      JsonObject errorJson = new JsonObject()
        .put("code", 400)
        .put("message", failure != null ? failure.getMessage() : "Something went wrong!");

      ctx.response()
        .putHeader("content-type", "application/json")
        .setStatusCode(400)
        .end(errorJson.encodePrettily());
    });

    router.errorHandler(500, ctx -> {
      Throwable failure = ctx.failure();

      logger.error("server error:", failure);

      JsonObject errorJson = new JsonObject()
        .put("code", 500)
        .put("message", failure != null ? failure.getMessage() : "Something went wrong!");

      ctx.response()
        .putHeader("content-type", "application/json")
        .setStatusCode(500)
        .end(errorJson.encodePrettily());
    });


    webClient = WebClient.create(vertx);

    return vertx.createHttpServer()
      .requestHandler(router)
      .rxListen(HTTP_PORT)
      .ignoreElement();
  }

  private void sendStatusCode(RoutingContext ctx, int code) {
    ctx.response().setStatusCode(code).end();
  }

  private void sendBadGateway(RoutingContext ctx, Throwable err) {
    logger.error("Woops", err);
    ctx.fail(400);
  }

  private void handleAuthError(RoutingContext ctx, Throwable err) {
    logger.error("Authentication error", err);
    ctx.fail(401);
  }

  private void sendToken(RoutingContext ctx, String token) {
    ctx.response().putHeader("Content-Type", "application/jwt").end(token);
  }

  private void checkUser(RoutingContext ctx) {
    String subject = ctx.user().principal().getString("sub");
    if (!ctx.pathParam("username").equals(subject)) {
      sendStatusCode(ctx, 403);
    } else {
      ctx.next();
    }
  }

  private void register(RoutingContext ctx) {
    webClient
      .post(3000, "localhost", "/register")
      .putHeader("Content-Type", "application/json")
      .expect(ResponsePredicate.SC_OK)
      .rxSendJson(ctx.getBodyAsJson())
      .subscribe(
        response -> sendStatusCode(ctx, response.statusCode()),
        err -> sendBadGateway(ctx, err));
  }

  private void token(RoutingContext ctx) {
    JsonObject payload = ctx.getBodyAsJson();
    String username = payload.getString("username");
    webClient
      .post(3000, "localhost", "/authenticate")
      .expect(ResponsePredicate.SC_SUCCESS)
      .rxSendJson(payload)
      .flatMap(resp -> fetchUserDetails(username))
      .map(resp -> resp.body().getString("deviceId"))
      .map(deviceId -> makeJwtToken(username, deviceId))
      .subscribe(
        token -> sendToken(ctx, token),
        err -> handleAuthError(ctx, err));
  }

  private Single<HttpResponse<JsonObject>> fetchUserDetails(String username) {
    return webClient
      .get(3000, "localhost", "/" + username)
      .expect(ResponsePredicate.SC_OK)
      .as(BodyCodec.jsonObject())
      .rxSend();
  }

  private String makeJwtToken(String username, String deviceId) {
    JsonObject claims = new JsonObject()
      .put("deviceId", deviceId);

    JWTOptions jwtOptions = new JWTOptions()
      .setAlgorithm("RS256")
      .setExpiresInMinutes(10_080) // 7 days
      .setIssuer("10k-steps-api")
      .setSubject(username);

    return jwtAuth.generateToken(claims, jwtOptions);
  }

  private void fetchUser(RoutingContext ctx) {
    webClient
      .get(3000, "localhost", "/" + ctx.pathParam("username"))
      .as(BodyCodec.jsonObject())
      .rxSend()
      .subscribe(
        resp -> forwardJsonOrStatusCode(ctx, resp),
        err -> sendBadGateway(ctx, err));
  }

  private void forwardJsonOrStatusCode(RoutingContext ctx, HttpResponse<JsonObject> resp) {
    if (resp.statusCode() != 200) {
      sendStatusCode(ctx, resp.statusCode());
    } else {
      ctx.response()
        .putHeader("Content-Type", "application/json")
        .end(resp.body().encode());
    }
  }

  private void updateUser(RoutingContext ctx) {
    webClient
      .put(3000, "localhost", "/" + ctx.pathParam("username"))
      .putHeader("Content-Type", "application/json")
      .expect(ResponsePredicate.SC_OK)
      .rxSendBuffer(ctx.getBody())
      .subscribe(
        resp -> ctx.response().end(),
        err -> sendBadGateway(ctx, err));
  }

  private void totalSteps(RoutingContext ctx) {
    String deviceId = ctx.user().principal().getString("deviceId");
    webClient
      .get(3001, "localhost", "/" + deviceId + "/total")
      .as(BodyCodec.jsonObject())
      .rxSend()
      .subscribe(
        resp -> forwardJsonOrStatusCode(ctx, resp),
        err -> sendBadGateway(ctx, err));
  }


  private void monthlySteps(RoutingContext ctx) {
    String deviceId = ctx.user().principal().getString("deviceId");
    String year = ctx.pathParam("year");
    String month = ctx.pathParam("month");
    webClient
      .get(3001, "localhost", "/" + deviceId + "/" + year + "/" + month)
      .as(BodyCodec.jsonObject())
      .rxSend()
      .subscribe(
        resp -> forwardJsonOrStatusCode(ctx, resp),
        err -> sendBadGateway(ctx, err));
  }

  private void dailySteps(RoutingContext ctx) {
    String deviceId = ctx.user().principal().getString("deviceId");
    String year = ctx.pathParam("year");
    String month = ctx.pathParam("month");
    String day = ctx.pathParam("day");
    webClient
      .get(3001, "localhost", "/" + deviceId + "/" + year + "/" + month + "/" + day)
      .as(BodyCodec.jsonObject())
      .rxSend()
      .subscribe(
        resp -> forwardJsonOrStatusCode(ctx, resp),
        err -> sendBadGateway(ctx, err));
  }

  public static void main(String[] args) {
    System.setProperty(
      "vertx.logger-delegate-factory-class-name",
      "io.vertx.core.logging.SLF4JLogDelegateFactory");

    Vertx vertx = Vertx.vertx();
    vertx.rxDeployVerticle(new PublicVerticle()).subscribe(
      ok -> logger.info("HTTP server started on port {}", HTTP_PORT),
      err -> logger.error("Woops", err));
  }
}
