package com.certmanage.server;
import java.util.*;
/** Fixed diagnostic descriptions; raw exceptions and authentication material never cross the API boundary. */
public class RemoteFailure extends IllegalArgumentException {
 private final String code,stage;
 public RemoteFailure(String code,String stage,String message){super(message);this.code=code;this.stage=stage;}
 public String code(){return code;}public String stage(){return stage;}
 public static RemoteFailure of(String protocol,String stage,Map<String,Object> config,Throwable failure){
  if(failure instanceof RemoteFailure known)return known;
  var causes=new ArrayList<Throwable>();for(Throwable e=failure;e!=null&&!causes.contains(e);e=e.getCause())causes.add(e);
  String text=causes.stream().map(e->Objects.toString(e.getMessage(),"")).reduce("",(a,b)->a+" "+b).toLowerCase(Locale.ROOT);
  if(causes.stream().anyMatch(e->e instanceof java.net.UnknownHostException||e instanceof java.nio.channels.UnresolvedAddressException))return new RemoteFailure("DNS_FAILED",stage,"服务器地址解析失败：请检查主机名和DNS配置");
  if(causes.stream().anyMatch(e->e instanceof java.net.SocketTimeoutException||e instanceof java.net.http.HttpTimeoutException||e instanceof java.util.concurrent.TimeoutException)||text.contains("timed out")||text.contains("timeout"))return new RemoteFailure("TIMEOUT",stage,"连接或读写超时：请检查网络、防火墙及服务器响应，可适当调整超时设置");
  if(causes.stream().anyMatch(e->e instanceof java.net.NoRouteToHostException))return new RemoteFailure("NETWORK_UNREACHABLE",stage,"目标网络不可达：请检查路由和防火墙规则");
  if(causes.stream().anyMatch(e->e instanceof java.net.ConnectException))return new RemoteFailure("CONNECTION_REFUSED",stage,"连接被拒绝：请检查主机、端口及目标服务是否已启动");
  if(causes.stream().anyMatch(e->e instanceof javax.net.ssl.SSLException||e instanceof java.security.cert.CertificateException))return new RemoteFailure("TLS_FAILED",stage,"HTTPS握手或证书验证失败：请检查服务端证书、域名及信任链");
  if("SFTP".equals(protocol)){
   if(stage.equals("KEY_LOADING"))return new RemoteFailure("SSH_KEY_INVALID",stage,"SSH私钥读取失败：请检查私钥格式、内容及私钥口令");
   if(stage.equals("AUTH"))return new RemoteFailure("AUTHENTICATION_FAILED",stage,"KEY".equals(config.get("authType"))?"SSH密钥认证失败：请检查用户名、私钥及服务器授权公钥配置":"密码认证失败：请检查用户名、密码，以及服务器是否允许密码登录");
   if(stage.equals("SFTP_SESSION"))return new RemoteFailure("SFTP_UNAVAILABLE",stage,"SSH已连接并认证，但无法打开SFTP服务：请检查服务器SFTP子系统配置");
   for(Throwable e:causes)if(e instanceof org.apache.sshd.sftp.common.SftpException s){if(s.getStatus()==3)return new RemoteFailure("PERMISSION_DENIED",stage,"SFTP权限不足：请检查远程目录和文件的读写权限");if(s.getStatus()==2)return new RemoteFailure("REMOTE_PATH_MISSING",stage,"远程目录或文件不存在：请检查目标路径");}
   if(text.contains("目标文件已存在"))return new RemoteFailure("TARGET_EXISTS",stage,"目标文件已存在，当前任务未启用覆盖");
   if(stage.equals("CONNECT"))return new RemoteFailure("SSH_CONNECT_FAILED",stage,"SSH连接或握手失败：请检查端口是否为SSH服务、网络及服务器算法兼容性");
  }
  return new RemoteFailure("REMOTE_OPERATION_FAILED",stage,protocol+"操作未完成：连接可能被关闭，或远端服务拒绝请求；若已提交上传，请核查远端结果");
 }
}
