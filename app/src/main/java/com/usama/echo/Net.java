package com.usama.echo;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The internet connection used to read YouTube video details for downloads. */
final class Net extends Downloader {
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    private static boolean ready;

    static synchronized void init() {
        if (ready) return;
        NewPipe.init(new Net());
        ready = true;
    }

    private Net() {}

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        HttpURLConnection con = (HttpURLConnection) new URL(request.url()).openConnection();
        try {
            con.setConnectTimeout(20000);
            con.setReadTimeout(30000);
            con.setRequestMethod(request.httpMethod());
            con.setRequestProperty("User-Agent", USER_AGENT);
            for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
                boolean first = true;
                for (String v : h.getValue()) {
                    if (first) con.setRequestProperty(h.getKey(), v);
                    else con.addRequestProperty(h.getKey(), v);
                    first = false;
                }
            }
            byte[] body = request.dataToSend();
            if (body != null) {
                con.setDoOutput(true);
                con.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = con.getOutputStream()) {
                    os.write(body);
                }
            }
            int code = con.getResponseCode();
            if (code == 429) throw new ReCaptchaException("YouTube asked for a captcha", request.url());
            String text = null;
            if (!"HEAD".equals(request.httpMethod())) {
                InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
                if (in != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    try (InputStream i = in) {
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = i.read(buf)) > 0) bos.write(buf, 0, r);
                    }
                    text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            Map<String, List<String>> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> e : con.getHeaderFields().entrySet()) {
                if (e.getKey() != null) headers.put(e.getKey(), e.getValue());
            }
            return new Response(code, con.getResponseMessage(), headers, text, con.getURL().toString());
        } finally {
            con.disconnect();
        }
    }
}
