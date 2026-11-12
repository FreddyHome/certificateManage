package com.certmanage;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication @EnableScheduling
public class CertificateManageApplication { public static void main(String[] args){SpringApplication.run(CertificateManageApplication.class,args);} }
