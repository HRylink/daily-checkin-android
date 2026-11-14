package com.velpro.checkin;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.json.JSONObject;

/** Local HTTP fixtures for Trae CN: deterministic derivatives, device proofs and claim rechecks. */
public class TraeTest {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static String read(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }
    static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b); x.close();
    }
    static class Fixture implements AutoCloseable {
        final HttpServer server; final String base;
        final List<String> seen = new ArrayList<>();
        int statusCalls, claimCalls, fallbackCalls, exchangeCalls, boundCalls;
        boolean checked, checkedFirst, enable = true, checkedAfterClaim = true, claimHttpError;
        String claimCode = "0", fallbackCode = "0", statusCode = "0";
        int statusHttp = 200; boolean statusNetworkFail, verifyMode, verifyNetworkFail;
        Trae.Proof lastProof;
        Trae.Auth lastAuth;
        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/", this::serve); server.start();
        }
        void serve(HttpExchange x) throws IOException {
            String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
            String body = read(x);
            seen.add(method + " " + path + " " + body);
            if (path.equals("/cloudide/api/v3/trae/oauth/ExchangeToken")) {
                exchangeCalls++;
                require(method.equals("POST"), "token exchange must POST");
                JSONObject j = new JSONObject(body);
                require(j.optString("ClientSecret").equals("-") && j.optString("RefreshToken").equals("refresh-1"), "exchange body wrong");
                send(x, 200, "{\"Result\":{\"Token\":\"T1\",\"RefreshToken\":\"R1\",\"TokenExpireAt\":4102444800}}");
            } else if (path.equals("/trae/api/v3/oauth/ExchangeToken")) {
                boundCalls++;
                require("Cloud-IDE-JWT T1".equals(x.getRequestHeaders().getFirst("Authorization")), "bound exchange auth missing");
                JSONObject j = new JSONObject(body);
                JSONObject proof = j.optJSONObject("DeviceProof");
                require(proof != null && proof.optLong("Timestamp") > 0 && proof.optString("Nonce").length() == 32,
                        "DeviceProof timestamp/nonce missing");
                lastProof = new Trae.Proof(proof.optString("Signature"), proof.optLong("Timestamp"), proof.optString("Nonce"));
                lastAuth = auth(j.optString("RefreshToken"));
                require(j.optString("IDEVersion").equals(j.optJSONObject("DeviceInfo").optString("ClientVersion")), "IDEVersion mismatch");
                send(x, 200, "{\"Result\":{\"Token\":\"T2\",\"RefreshToken\":\"R2\",\"TokenExpireAt\":4102444800}}");
            } else if (path.equals("/trae/api/v2/ug/checkin_credits/status")) {
                statusCalls++;
                if (statusCalls == 1 || verifyMode) checked = checkedFirst;
                else checked = checkedAfterClaim;
                require(method.equals("POST") && body.equals("{}"), "status must POST {}");
                require("Cloud-IDE-JWT T2".equals(x.getRequestHeaders().getFirst("Authorization")), "ug auth missing");
                require(x.getRequestHeaders().getFirst("x-device-id").matches("\\d{15}"), "ugDeviceId must be 15 digits");
                require("ASUS TUF Gaming A15 FA507RM_FA507RM".equals(x.getRequestHeaders().getFirst("x-device-brand")), "ug brand missing");
                if (verifyNetworkFail) { x.close(); return; }
                if (!statusCode.equals("0")) { send(x, statusHttp, "{\"code\":" + statusCode + ",\"message\":\"业务错误\"}"); return; }
                send(x, 200, "{\"code\":0,\"checked_in\":" + checked + ",\"enable\":" + enable + ",\"credits\":9,\"extra_credits\":5}");
            } else if (path.equals("/trae/api/v2/ug/checkin_credits/claim")) {
                boolean fallback = "VSCode 1.107.1 (TRAE SOLO CN)".equals(x.getRequestHeaders().getFirst("User-Agent"));
                if (fallback) {
                    fallbackCalls++;
                    require(body.equals("{}"), "clientShape fallback must POST {}");
                    require(x.getRequestHeaders().getFirst("x-market-user-id").matches(
                            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), "shaped market UUID wrong");
                    require(x.getRequestHeaders().getFirst("x-device-id").matches("\\d{15}"), "shaped device wrong");
                    require(x.getRequestHeaders().getFirst("vscode-sessionid").matches("[0-9a-f]{64}"), "shaped session wrong");
                    require(x.getRequestHeaders().getFirst("x-tt-trace-id").matches("00-[0-9a-f]{16}-01"), "trace id wrong");
                    require(x.getRequestHeaders().getFirst("x-request-id").length() == 36, "request id wrong");
                    require("CN".equals(x.getRequestHeaders().getFirst("x-user-region")), "region missing");
                } else {
                    claimCalls++;
                    require(new JSONObject(body).optInt("req_source") == 1, "ug claim must send req_source=1");
                    require("Cloud-IDE-JWT T2".equals(x.getRequestHeaders().getFirst("Authorization")), "claim auth missing");
                }
                if (claimHttpError && !fallback) { send(x, 503, "{\"code\":0}"); return; }
                String code = fallback ? fallbackCode : claimCode;
                if (fallback || !code.equals("9074")) checkedAfterClaim = checkedAfterClaim && code.equals("0");
                send(x, 200, "{\"code\":" + code + ",\"credits\":12,\"extra_credits\":8,\"message\":\"结果\"}");
            } else send(x, 404, "{\"code\":0,\"message\":\"unknown\"}");
        }
        public void close() { server.stop(0); }
    }
    static Trae client(Fixture f) { return new Trae(f.base, f.base, 100, 100); }
    static Trae.Auth auth(String refresh) {
        Trae.Auth a = new Trae.Auth();
        a.token = "T2"; a.refresh = refresh; a.uid = "uid-42"; a.user = "Trae 用户";
        a.deviceId = "1234567890123456"; a.machineId = "0123456789abcdef0123456789abcdef";
        a.deviceKey = ""; a.expires = 4102444800000L;
        return a;
    }
    public static void main(String[] args) throws Exception {
        int groups = 0;
        Trae.Device d = Trae.newDevice();
        require(d.deviceId.matches("[1-9]\\d{15}"), "deviceId must be 16 digits, first non-zero");
        require(d.machineId.matches("[0-9a-f]{32}"), "machineId must be 32 hex");
        require(d.publicPem.startsWith("-----BEGIN PUBLIC KEY-----"), "device public key PEM missing");
        require(d.privatePem.startsWith("-----BEGIN PRIVATE KEY-----"), "device private key PEM missing");
        require(Trae.ugDeviceId("123456789012345", "uid-42").equals("123456789012345"), "15-digit device must be used directly");
        require(Trae.ugDeviceId("1234567890123456", "uid-42").equals("208346249201553"), "ugDevice vector wrong");
        require(Trae.ugDeviceId("1234567890123456", "uid-42").matches("[1-9]\\d{14}"), "ugDevice must be 15 digits");
        require(Trae.ugDeviceId("1234567890123456", "uid-42").equals(Trae.ugDeviceId("1234567890123456", "uid-42")), "ugDevice must be stable");
        require(Trae.shapeDevice("uid-42").equals("939629846158878"), "shapeDevice vector wrong");
        require(Trae.shapeMarketUser("uid-42").equals("15cfaf80-b94e-424a-9e5d-75ff8bd9e9fc"), "shapeMarketUser vector wrong");
        require(Trae.sessionId("uid-42").equals("bfcfa4b8cad147a331885321be38b726deefa0a37d7ea2fab6cb1ad6ebc5a2cc"), "session vector wrong");
        Trae.Proof p = Trae.proof("/trae/api/v3/oauth/ExchangeToken", "client-1", "refresh-1", d.privatePem, 1700000000L, "0123456789abcdef0123456789abcdef");
        // ECDSA signatures use a random nonce: verify with the public key, never compare two signatures byte-for-byte.
        byte[] proofBytes = Trae.proofText("/trae/api/v3/oauth/ExchangeToken", "client-1", "refresh-1", 1700000000L, p.nonce).getBytes(StandardCharsets.UTF_8);
        PrivateKey key = Trae.privateKey(d.privatePem);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key); signer.update(proofBytes);
        byte[] ownSignature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(Trae.publicKey(d.publicPem));
        verifier.update(Trae.proofText("/trae/api/v3/oauth/ExchangeToken", "client-1", "refresh-1", 1700000000L, p.nonce).getBytes(StandardCharsets.UTF_8));
        require(verifier.verify(Base64.getDecoder().decode(p.signature)), "DeviceProof not verifiable with public key");
        Signature ownVerifier = Signature.getInstance("SHA256withECDSA");
        ownVerifier.initVerify(Trae.publicKey(d.publicPem)); ownVerifier.update(proofBytes);
        require(ownVerifier.verify(ownSignature), "stored private key PEM cannot sign SHA256withECDSA");
        groups++;
        try (Fixture f = new Fixture()) {
            Trae.Auth a = client(f).completeSignIn("https://www.trae.cn", "refresh-1", "uid-42", "Trae 用户", d);
            require(f.exchangeCalls == 1 && f.boundCalls == 2, "token + two bound exchanges required");
            require(a.token.equals("T2") && a.refresh.equals("R2") && a.expires == 4102444800000L, "bound token/refresh not persisted shape");
            verifier.initVerify(Trae.publicKey(d.publicPem));
            verifier.update(Trae.proofText("/trae/api/v3/oauth/ExchangeToken", f.lastAuth.clientId, f.lastAuth.refresh,
                    f.lastProof.timestamp, f.lastProof.nonce).getBytes(StandardCharsets.UTF_8));
            require(verifier.verify(Base64.getDecoder().decode(f.lastProof.signature)), "bound DeviceProof failed verification");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            f.checkedFirst = true; // already checked in before this run
            Trae.R r = client(f).checkIn(auth("R2"));
            require(r.outcome.equals("done") && r.credit == 9 && f.claimCalls == 0, "checked-in status must not claim");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            Trae.R r = client(f).checkIn(auth("R2"));
            require(r.outcome.equals("claimed") && r.credit == 12 && f.claimCalls == 1 && f.statusCalls == 2,
                    "claim code 0 requires status recheck");
            require(f.seen.stream().anyMatch(s -> s.contains("\"req_source\":1")), "claim body missing req_source");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            f.checkedAfterClaim = false;
            Trae.R r = client(f).checkIn(auth("R2"));
            require(!r.ok() && r.outcome.equals("ineligible") && f.claimCalls == 1 && f.statusCalls == 2,
                    "code 0 with unchecked status must not report success, never claim again");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            f.claimCode = "9095"; f.checkedAfterClaim = false;
            Trae.R r = client(f).checkIn(auth("R2"));
            require(r.outcome.equals("ineligible") && f.claimCalls == 1 && f.fallbackCalls == 0, "9095 is final ineligible");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            f.claimCode = "9074"; f.fallbackCode = "0"; f.checkedAfterClaim = true;
            Trae.R r = client(f).checkIn(auth("R2"));
            require(r.outcome.equals("claimed") && f.claimCalls == 1 && f.fallbackCalls == 1 && f.statusCalls == 2,
                    "9074 must use clientShape once and recheck status");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            f.claimHttpError = true; f.checkedAfterClaim = false; // ambiguous claim; read-only status does not confirm
            Trae.R r = client(f).checkIn(auth("R2"));
            require(!r.ok() && r.outcome.equals("ineligible") && f.claimCalls == 1 && f.fallbackCalls == 0 && f.statusCalls == 2,
                    "ambiguous claim must only recheck status, never replay");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            f.enable = false;
            Trae.R r = client(f).checkIn(auth("R2"));
            require(r.outcome.equals("inactive") && f.claimCalls == 0, "disabled activity must not claim");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            f.statusCode = "1001";
            Trae.R r = client(f).checkIn(auth("R2"));
            require(r.outcome.equals("failed") && r.needsAuth && f.claimCalls == 0, "code 1001 must require re-login");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            f.statusCode = "20101";
            Trae.R r = client(f).checkIn(auth("R2"));
            require(r.outcome.equals("failed") && r.needsAuth && f.claimCalls == 0, "code 20101 must require re-login");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            f.verifyMode = true;
            SiteRegistry.Verify v = client(f).verify(auth("R2"));
            require(v.ok && !v.invalid && !v.network && f.statusCalls == 1 && f.claimCalls == 0,
                    "Trae read-only verification must pass without claiming");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            f.verifyMode = true; f.statusCode = "1001"; f.statusHttp = 401;
            SiteRegistry.Verify v = client(f).verify(auth("R2"));
            require(v.invalid && !v.network && !v.ok && f.claimCalls == 0,
                    "Trae 401/code 1001 must be invalid credentials");
            groups++;
        }
        try (Fixture f = new Fixture()) {
            f.verifyMode = true; f.verifyNetworkFail = true;
            SiteRegistry.Verify v = client(f).verify(auth("R2"));
            require(v.network && !v.invalid && !v.ok && f.claimCalls == 0,
                    "Trae network failure must be distinguished from invalid credentials");
            groups++;
        }
        System.out.println("PASS TraeTest: " + groups + " groups (vectors, ECDSA proof, bound exchange, claim recheck, 9074 fallback, 9095, no replay, read-only verification)");
    }
}
