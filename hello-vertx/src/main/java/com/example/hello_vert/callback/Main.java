package com.example.hello_vert.callback;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

public class Main {

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();

    vertx.deployVerticle("com.example.hello_vert.sensor.HeatSensor",
      new DeploymentOptions().setConfig(new JsonObject()
        .put("http.port", 3000)));

    vertx.deployVerticle("com.example.hello_vert.sensor.HeatSensor",
      new DeploymentOptions().setConfig(new JsonObject()
        .put("http.port", 3001)));

    vertx.deployVerticle("com.example.hello_vert.sensor.HeatSensor",
      new DeploymentOptions().setConfig(new JsonObject()
        .put("http.port", 3002)));

    vertx.deployVerticle("com.example.hello_vert.snapshot.SnapshotService");
    vertx.deployVerticle("com.example.hello_vert.callback.CollectorService");
  }
}
