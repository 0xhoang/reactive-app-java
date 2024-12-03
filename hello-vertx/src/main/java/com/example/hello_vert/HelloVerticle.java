package com.example.hello_vert;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HelloVerticle extends AbstractVerticle {
    private static int numberOfConnections = 0;

    private final Logger logger = LoggerFactory.getLogger(HelloVerticle.class);

    private long counter = 1;

    @Override
    public void start() {
        vertx.createNetServer()
                .connectHandler(HelloVerticle::handleNewClient)
                .listen(3000);

        vertx.setPeriodic(5000, id -> {
            logger.info("tick");
        });

        vertx.createHttpServer()
                .requestHandler(req -> {
                    logger.info("Request #{} from {}", counter++, req.remoteAddress().host());
                    req.response().end(howMany());
                })
                .listen(8080);

        logger.info("Open http://localhost:8080/");
    }

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new HelloVerticle());
    }

    private static void handleNewClient(NetSocket socket) {
        numberOfConnections++;
        socket.handler(buffer -> {
            socket.write(buffer);
            if (buffer.toString().endsWith("/quit\n")) {
                socket.close();
            }
        });
        socket.closeHandler(v -> numberOfConnections--);
    }

    private static String howMany() {
        return "We now have " + numberOfConnections + " connections";
    }
}
