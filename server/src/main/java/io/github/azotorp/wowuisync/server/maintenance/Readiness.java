package io.github.azotorp.wowuisync.server.maintenance;

import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.crypto.CertFingerprint;
import io.github.azotorp.wowuisync.common.protocol.*;
import io.github.azotorp.wowuisync.server.config.*;
import io.github.azotorp.wowuisync.server.security.TlsKeys;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.net.InetSocketAddress;
import java.util.UUID;

/** Application-level readiness: pinned local TLS identity and a valid protocol handshake. */
public final class Readiness {
 public static void check(DataLayout layout)throws Exception {
  var cfg=ConfigIO.loadSettings(layout);
  String expected=TlsKeys.fingerprint(TlsKeys.load(layout.keystore()));
  SSLContext tls=SSLContext.getInstance("TLSv1.3");
  tls.init(null,new TrustManager[]{new X509TrustManager(){
   public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
   public void checkClientTrusted(X509Certificate[] c,String a)throws java.security.cert.CertificateException{throw new java.security.cert.CertificateException("Client trust unavailable");}
   public void checkServerTrusted(X509Certificate[] c,String a)throws java.security.cert.CertificateException {
    try {if(c.length==0 || !expected.equals(CertFingerprint.of(c[0])))throw new java.security.cert.CertificateException("TLS identity mismatch");}
    catch(java.security.cert.CertificateException e){throw e;}catch(Exception e){throw new java.security.cert.CertificateException(e);}
   }
  }},null);
  String host=cfg.bindAddress.equals("0.0.0.0")?"127.0.0.1":cfg.bindAddress.equals("::")?"::1":cfg.bindAddress;
  try(SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket()) {
   socket.connect(new InetSocketAddress(host,cfg.port),3000);socket.setSoTimeout(3000);socket.startHandshake();
   try(Wire wire=new Wire(socket.getInputStream(),socket.getOutputStream(),socket)) {
    Frame frame=wire.call(new Msg.Hello(Version.PROTOCOL_VERSION,Version.APP_VERSION,Codec.supported(),UUID.randomUUID().toString(),"local readiness"));
    if(frame.type()!=MessageType.HELLO_OK)throw new java.io.IOException("Server did not accept the application handshake");
    Msg.HelloOk hello=frame.message(Msg.HelloOk.class);
    if(hello.protocolVersion()!=Version.PROTOCOL_VERSION || !hello.appVersion().equals(Version.APP_VERSION))throw new java.io.IOException("Running server version differs from the replacement JAR");
   }
  }
  System.out.println("ready version="+Version.APP_VERSION+" protocol="+Version.PROTOCOL_VERSION+" tls="+expected);
 }
 private Readiness(){}
}
