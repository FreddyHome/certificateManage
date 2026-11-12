package com.certmanage.server;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RemoteFailureTest {
 @Test void explainsCauseWithoutLeakingRawException(){var failure=RemoteFailure.of("SFTP","AUTH",Map.of("authType","PASSWORD"),new java.io.IOException("password=super-secret"));assertEquals("AUTHENTICATION_FAILED",failure.code());assertTrue(failure.getMessage().contains("密码认证失败"));assertFalse(failure.getMessage().contains("super-secret"));assertEquals("DNS_FAILED",RemoteFailure.of("SFTP","CONNECT",Map.of(),new java.net.UnknownHostException("secret-host")).code());assertEquals("CONNECTION_REFUSED",RemoteFailure.of("SFTP","CONNECT",Map.of(),new java.net.ConnectException("Connection refused")).code());assertEquals("TIMEOUT",RemoteFailure.of("SFTP","CONNECT",Map.of(),new java.net.SocketTimeoutException("secret")).code());}
}
