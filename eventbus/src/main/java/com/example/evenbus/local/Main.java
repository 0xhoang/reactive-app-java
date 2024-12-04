package com.example.evenbus.local;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;

public class Main {

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle("com.example.evenbus.HeatSensor", new DeploymentOptions().setInstances(4));
    vertx.deployVerticle("com.example.evenbus.Listener");
    vertx.deployVerticle("com.example.evenbus.SensorData");
    vertx.deployVerticle("com.example.evenbus.HttpServer");
  }
}
