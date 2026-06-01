package com.lacnet.besu.gas.usage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecorderRpc} sobre el JSON-RPC del propio nodo, usando el {@link HttpClient} del JDK (sin
 * dependencias extra). El recorder lo apunta a su RPC local (p.ej. {@code http://localhost:4545}).
 *
 * <p>Parsea las respuestas con regex en vez de una librería JSON: los dos métodos que usamos
 * ({@code eth_getTransactionCount}, {@code eth_sendRawTransaction}) devuelven un único campo
 * {@code "result"} hex o un {@code "error"}. Evita shadowear Jackson/gson en el JAR del plugin.
 */
public final class JsonRpcRecorderRpc implements RecorderRpc {

    private static final Logger LOG = LoggerFactory.getLogger(JsonRpcRecorderRpc.class);

    private static final Pattern RESULT = Pattern.compile("\"result\"\\s*:\\s*\"(0x[0-9a-fA-F]*)\"");
    private static final Pattern ERROR_MSG = Pattern.compile("\"error\".*?\"message\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpClient http;
    private final URI endpoint;

    public JsonRpcRecorderRpc(final String nodeUrl) {
        this(nodeUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    /** Visible para tests — permite inyectar un HttpClient. */
    JsonRpcRecorderRpc(final String nodeUrl, final HttpClient http) {
        this.endpoint = URI.create(nodeUrl);
        this.http = http;
    }

    @Override
    public long pendingNonce(final Address account) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_getTransactionCount\","
                + "\"params\":[\"" + account.toHexString() + "\",\"pending\"]}";
        String result = call(body);
        return Long.decode(result); // hex "0x..." → long
    }

    @Override
    public String sendRawTransaction(final Bytes signedRawTx) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_sendRawTransaction\","
                + "\"params\":[\"" + signedRawTx.toHexString() + "\"]}";
        return call(body);
    }

    private String call(final String jsonBody) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        final HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("JSON-RPC IO error contra " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("JSON-RPC interrumpido", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("JSON-RPC HTTP " + response.statusCode() + ": " + response.body());
        }
        String payload = response.body();
        Matcher err = ERROR_MSG.matcher(payload);
        if (err.find()) {
            throw new IllegalStateException("JSON-RPC error: " + err.group(1));
        }
        Matcher res = RESULT.matcher(payload);
        if (!res.find()) {
            throw new IllegalStateException("JSON-RPC sin 'result': " + payload);
        }
        return res.group(1);
    }
}
