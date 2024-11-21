package com.example.congrats_services;

import io.vertx.ext.mail.MailConfig;

class MailerConfig {

  static MailConfig config() {
    return new MailConfig()
      .setHostname("localhost")
      .setPort(1025);
  }
}
