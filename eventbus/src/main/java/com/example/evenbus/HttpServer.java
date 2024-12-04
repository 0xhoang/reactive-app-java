package com.example.evenbus;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.TimeoutStream;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpServer extends AbstractVerticle {
  private final Logger logger = LoggerFactory.getLogger(HttpServer.class);

  @Override
  public void start() {
    vertx.createHttpServer()
      .requestHandler(this::handler)
      .listen(config().getInteger("port", 8080));
  }

  private void handler(HttpServerRequest request) {
    if ("/".equals(request.path())) {
      request.response().sendFile("index.html");
    } else if ("/sse".equals(request.path())) {
      sse(request);
    } else {
      request.response().setStatusCode(404);
    }
  }

  private void sse(HttpServerRequest request) {
    HttpServerResponse response = request.response();
    response
      .putHeader("Content-Type", "text/event-stream")
      .putHeader("Cache-Control", "no-cache")
      .setChunked(true);

    MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer("sensor.updates");
    consumer.handler(msg -> {
      logger.info("send request > update");
      response.write("event: update\n");
      response.write("data: " + msg.body().encode() + "\n\n");
    });


    TimeoutStream ticks = vertx.periodicStream(1000);
    ticks.handler(id -> {
      vertx.eventBus().<JsonObject>request("sensor.average", "", reply -> {
        if (reply.succeeded()) {
          logger.info("send request > average");
          response.write("event: average\n");
          response.write("data: " + reply.result().body().encode() + "\n\n");
        }
      });
    });

    response.endHandler(v -> {
      consumer.unregister();
      ticks.cancel();
    });
  }
}
