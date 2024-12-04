package com.example.hello_vert.future;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class Intro {

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();

    Promise<String> promise = Promise.promise();

    System.out.println("Waiting...");

    //1. Asynchronous operation
    vertx.setTimer(5000, id -> {
      if (System.currentTimeMillis() % 2L == 0L) {
        promise.complete("Ok!");
      } else {
        promise.fail(new RuntimeException("Bad luck..."));
      }
    });

    //2. Derive a future from a promise, and then return it.
    Future<String> future = promise.future();

    //3. Callback for when the promise is completed
    future.onSuccess(System.out::println) //success
      .onFailure(err -> System.out.println(err.getMessage())); //failed

    //4. flatMap result (sleep 300 and print lasted)
    promise.future()
      //4.1 Recover. The recover operation is called when the promise is failed
      .recover(err -> Future.succeededFuture("Let's say it's ok!"))
      .map(String::toUpperCase)
      .flatMap(str -> {
        Promise<String> next = Promise.promise();
        //sleep 3000
        vertx.setTimer(3000, id -> next.complete(">>> " + str));
        return next.future();
      })
      .onSuccess(System.out::println);


    //5. thenApply result
    CompletionStage<String> cs = promise.future().toCompletionStage();
    cs.thenApply(String::toUpperCase)
      .thenApply(str -> "~~~ " + str)
      .whenComplete((str, err) -> {
        if (err == null) {
          System.out.println(str);
        } else {
          System.out.println("Oh... " + err.getMessage());
        }
      });


    //Create a CompletableFuture from an asynchronous operation.
    CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> {
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }

      return "5 seconds have elapsed";
    });

    //Convert to a Vert.x future, and dispatch on a Vert.x context.
    Future
      .fromCompletionStage(cf, vertx.getOrCreateContext())
      .onSuccess(System.out::println)
      .onFailure(Throwable::printStackTrace);
  }
}
