package com.encircle360.oss.bankbote;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Delivers booked bank transactions from a transaction source to the ERPNext of each company. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BankboteApplication {

  public static void main(String[] args) {
    SpringApplication.run(BankboteApplication.class, args);
  }
}
