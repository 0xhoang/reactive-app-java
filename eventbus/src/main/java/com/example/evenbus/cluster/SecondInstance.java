package com.example.evenbus.cluster;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SecondInstance {

  private static final Logger logger = LoggerFactory.getLogger(SecondInstance.class);

  public static void main(String[] args) {
    Vertx.clusteredVertx(new VertxOptions(), ar -> {
      if (ar.succeeded()) {
        logger.info("Second instance has been started");
        Vertx vertx = ar.result();
        vertx.deployVerticle("com.example.evenbus.HeatSensor", new DeploymentOptions().setInstances(4));
        vertx.deployVerticle("com.example.evenbus.Listener");
        vertx.deployVerticle("com.example.evenbus.SensorData");
        JsonObject conf = new JsonObject().put("port", 8081);
        vertx.deployVerticle("com.example.evenbus.HttpServer", new DeploymentOptions().setConfig(conf));
      } else {
        logger.error("Could not start", ar.cause());
      }
    });
  }
}
