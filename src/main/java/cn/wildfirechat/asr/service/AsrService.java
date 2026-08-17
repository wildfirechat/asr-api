package cn.wildfirechat.asr.service;

import cn.wildfirechat.asr.jpa.Record;
import cn.wildfirechat.asr.jpa.RecordRepository;
import cn.wildfirechat.asr.model.ChatCompletionRequest;
import cn.wildfirechat.asr.model.ChatCompletionStreamResponse;
import cn.wildfirechat.asr.model.ChatMessage;

import java.util.Arrays;
import com.google.gson.Gson;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class AsrService {
    private static final Logger LOG = LoggerFactory.getLogger(AsrService.class);

    private String tempDir;

    @Autowired
    private RecordRepository mRecordRepository;

    @Value("${asr.max_audio_file_size}")
    private long maxAudioFileSize;

    @Value("${asr.server_url}")
    private String mAsrServerUrl;

    @Value("${llm.server_url}")
    private String mLlmServerUrl;

    @Value("${llm.model_name}")
    private String mModelName;

    @Value("${llm.api_key:}")
    private String mLlmApiKey;

    @Value("${asr.use_llm_correct}")
    private boolean useLLMCorrect;

    @Value("${asr.reuse_history}")
    private boolean reuseHistory;

    private ExecutorService executor;

    private CloseableHttpClient asrHttpClient;
    private CloseableHttpClient llmHttpClient;

    @PostConstruct
    void init() {
        tempDir = System.getProperty("java.io.tmpdir");
        asrHttpClient = createHttpClient(5000, 60000);
        llmHttpClient = createHttpClient(5000, 120000);
        int processors = Runtime.getRuntime().availableProcessors();
        executor = new ThreadPoolExecutor(
                processors * 4,
                processors * 40,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(processors * 100)
        );
    }

    private CloseableHttpClient createHttpClient(int connectTimeout, int socketTimeout) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeout)
                .setSocketTimeout(socketTimeout)
                .setConnectionRequestTimeout(5000)
                .build();
        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setMaxConnTotal(200)
                .setMaxConnPerRoute(100)
                .build();
    }

    @PreDestroy
    void destroy() {
        closeHttpClient(asrHttpClient, "asrHttpClient");
        closeHttpClient(llmHttpClient, "llmHttpClient");
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    private void closeHttpClient(CloseableHttpClient client, String name) {
        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                LOG.error("Failed to close {}", name, e);
            }
        }
    }

    public ResponseBodyEmitter onRecognize(String url, boolean noLlm, boolean noReuse, String appId) {
        ResponseBodyEmitter emitter = new SseEmitter();
        String requestId = UUID.randomUUID().toString().replace("-", "");
        long requestReceivedTime = System.currentTimeMillis();
        LOG.info("[{}] request received, url={}, noLlm={}, noReuse={}, appId={}", requestId, url, noLlm, noReuse, appId);

        CompletableFuture.runAsync(() -> {
            if (reuseHistory && !noReuse) {
                List<Record> existRecord = mRecordRepository.findByUrl(url);
                if (existRecord != null && !existRecord.isEmpty()) {
                    for (Record record : existRecord) {
                        if (StringUtils.hasText(record.text)) {
                            try {
                                emitter.send(record.text);
                                emitter.complete();
                            } catch (IOException e) {
                                LOG.error("[{}] failed to send history record", requestId, e);
                            }
                            return;
                        }
                    }
                }
            }

            boolean llm = !noLlm && useLLMCorrect && StringUtils.hasText(mLlmServerUrl) && StringUtils.hasText(mModelName);
            String savePath = null;
            Record record = new Record();
            record.receiveTimestamp = requestReceivedTime;
            record.url = url;
            record.appId = appId;
            try {
                savePath = tempDir + "/tempfiles" + UUID.randomUUID(); // 保存到本地的路径
                copyURLToFile(new URL(url), new File(savePath));
                long downloadDoneTime = System.currentTimeMillis();
                record.downloadDuration = (int)(downloadDoneTime - requestReceivedTime);
                LOG.info("[{}] download completed in {}ms", requestId, record.downloadDuration);

                HttpPost httpPost = new HttpPost(mAsrServerUrl);
                MultipartEntityBuilder builder = MultipartEntityBuilder.create();
                File file = new File(savePath);
                builder.addBinaryBody("file", file, ContentType.DEFAULT_BINARY, file.getName());
                HttpEntity multipart = builder.build();
                httpPost.setEntity(multipart);

                long asrStartTime = System.currentTimeMillis();

                HttpResponse response = asrHttpClient.execute(httpPost);
                int statusCode = response.getStatusLine().getStatusCode();

                StringBuilder sb = new StringBuilder();

                if (statusCode == 200) {
                    HttpEntity responseEntity = response.getEntity();
                    if (responseEntity != null) {
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(responseEntity.getContent(), StandardCharsets.UTF_8))) {
                            String line;
                            long firstReceiveTime = 0;
                            boolean asrSuccess = true;
                            while ((line = br.readLine()) != null) {
                                LOG.info("[{}] asr response: {}", requestId, line);
                                if (line.startsWith("[")) {
                                    float f = Float.parseFloat(line.substring(1, line.indexOf("]")-1).split("-")[1]);
                                    record.audioDuration = (int)(f*1000);
                                    if (firstReceiveTime == 0) {
                                        firstReceiveTime = System.currentTimeMillis();
                                        record.firstResponseDuration = (int)(firstReceiveTime - asrStartTime);
                                        LOG.info("[{}] asr first response received in {}ms", requestId, record.firstResponseDuration);
                                    }
                                    line = line.trim();
                                    if(line.startsWith("[")) {
                                        line = line.substring(line.lastIndexOf("]")+1);
                                    }
                                    if(line.startsWith("<")) {
                                        line = line.substring(line.lastIndexOf(">")+1);
                                    }
                                    if(StringUtils.hasText(line)) {
                                        if (llm) {
                                            sb.append(line);
                                        } else {
                                            if (line.contains("。") || line.contains("，") || line.contains("？") || line.contains("！")) {
                                                line = line.replace("。", " ").replace("，", " ").replace("？", " ").replace("！", " ");
                                            }
                                            sb.append(line);
                                            emitter.send(line); // 发送数据
                                        }
                                    }
                                } else {
                                    emitter.send("网络错误："+line);
                                    asrSuccess = false;
                                    break;
                                }
                            }
                            if (asrSuccess) {
                                long asrDoneTime = System.currentTimeMillis();
                                long asrDuration = asrDoneTime - asrStartTime;
                                LOG.info("[{}] asr completed in {}ms, audioDuration={}ms", requestId, asrDuration, record.audioDuration);
                                String text = sb.toString().trim();
                                if (llm && StringUtils.hasText(text)) {
                                    correctText(text, emitter, record, asrStartTime, requestId);
                                } else {
                                    record.text = text;
                                    record.workDuration = (int) (System.currentTimeMillis() - asrStartTime);
                                }
                            }
                            record.success = asrSuccess?1:0;
                        }
                    }
                } else {
                    emitter.send("网络错误："+statusCode);
                }
                emitter.complete(); // 完成发送
                LOG.info("[{}] request completed in {}ms, success={}", requestId, System.currentTimeMillis() - requestReceivedTime, record.success);
            } catch (Exception e) {
                LOG.error("[{}] ASR request exception", requestId, e);
                try {
                    emitter.send(e.getLocalizedMessage() + "\n");
                } catch (IOException ex) {
                    LOG.error("[{}] failed to send error message", requestId, ex);
                }
                emitter.complete(); // 完成发送
            } finally {
                deleteTempFile(savePath);
                try {
                    mRecordRepository.save(record);
                } catch (Exception e) {
                    LOG.error("[{}] failed to save record", requestId, e);
                }
            }
        }, executor);

        return emitter;
    }

    private boolean correctText(String text, ResponseBodyEmitter emitter, Record record, long asrStartTime, String requestId) {
        text = text.replaceAll("\n", "");
        StringBuilder sb = new StringBuilder();
        boolean success = false;
        long llmStartTime = System.currentTimeMillis();
        LOG.info("[{}] llm request started", requestId);

        try {
            HttpPost httpPost = new HttpPost(mLlmServerUrl);
            httpPost.setHeader("Content-Type", "application/json");
            if (StringUtils.hasText(mLlmApiKey)) {
                httpPost.setHeader("Authorization", "Bearer " + mLlmApiKey);
            }

            ChatCompletionRequest request = new ChatCompletionRequest(mModelName, Arrays.asList(
                    new ChatMessage("system", "你是一名文本校对助手。请对下面的文本添加合适的标点符号，保持原意不变，不要翻译、不要扩写、不要总结，直接输出处理后的文本。"),
                    new ChatMessage("user", text)
            ));

            httpPost.setEntity(new StringEntity(new Gson().toJson(request), StandardCharsets.UTF_8));
            HttpResponse response = llmHttpClient.execute(httpPost);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 200) {
                HttpEntity responseEntity = response.getEntity();
                if (responseEntity != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(responseEntity.getContent(), StandardCharsets.UTF_8))) {
                        String line;
                        boolean firstChunk = true;
                        while ((line = br.readLine()) != null) {
                            if (!StringUtils.hasText(line)) {
                                continue;
                            }
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6);
                                if ("[DONE]".equals(data)) {
                                    success = true;
                                    break;
                                }
                                ChatCompletionStreamResponse streamResponse = new Gson().fromJson(data, ChatCompletionStreamResponse.class);
                                if (streamResponse != null && streamResponse.choices != null && !streamResponse.choices.isEmpty()) {
                                    ChatCompletionStreamResponse.Choice choice = streamResponse.choices.get(0);
                                    if (choice.delta != null && StringUtils.hasText(choice.delta.content)) {
                                        if (firstChunk) {
                                            firstChunk = false;
                                            LOG.info("[{}] llm first response received in {}ms", requestId, System.currentTimeMillis() - llmStartTime);
                                        }
                                        emitter.send(choice.delta.content);
                                        sb.append(choice.delta.content);
                                    }
                                    if ("stop".equals(choice.finish_reason)) {
                                        success = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LOG.error("[{}] llm request failed, status code: {}", requestId, statusCode);
            }
        } catch (Exception e) {
            LOG.error("[{}] llm request exception", requestId, e);
        }

        long llmDoneTime = System.currentTimeMillis();
        LOG.info("[{}] llm completed in {}ms, success={}", requestId, llmDoneTime - llmStartTime, success);

        if (success && sb.length() > 0) {
            record.text = sb.toString();
        } else {
            if (sb.length() == 0) {
                try {
                    emitter.send(text);
                } catch (IOException e) {
                    LOG.error("[{}] failed to send fallback text", requestId, e);
                }
            } else {
                LOG.warn("[{}] llm stream incomplete, keep original text in record", requestId);
            }
            record.text = text;
        }
        record.workDuration = (int) (System.currentTimeMillis() - asrStartTime);
        return success;
    }

    public long copyURLToFile(URL source, File destination) throws IOException {
        try (InputStream input = source.openStream();
             OutputStream output = openOutputStream(destination, false)) {
            byte[] buffer = new byte[8192];
            long count;
            int n;
            for (count = 0L; -1 != (n = input.read(buffer)); count += (long) n) {
                output.write(buffer, 0, n);
                if (count > maxAudioFileSize) {
                    throw new IOException("File too large!");
                }
            }
            output.flush();
            return count;
        }
    }

    private void deleteTempFile(String savePath) {
        if (savePath == null) {
            return;
        }
        File fileToDelete = new File(savePath);
        int retries = 3;
        for (int i = 0; i < retries; i++) {
            try {
                if (!fileToDelete.exists()) {
                    return;
                }
                if (fileToDelete.delete()) {
                    return;
                }
                LOG.warn("Failed to delete temp file {}, retrying {}/{}", savePath, i + 1, retries);
                Thread.sleep(50);
            } catch (Exception e) {
                LOG.error("Exception while deleting temp file {}", savePath, e);
            }
        }
        LOG.error("Failed to delete temp file {} after {} retries", savePath, retries);
    }

    public static FileOutputStream openOutputStream(File file, boolean append) throws IOException {
        if (file.exists()) {
            if (file.isDirectory()) {
                throw new IOException("File '" + file + "' exists but is a directory");
            }

            if (!file.canWrite()) {
                throw new IOException("File '" + file + "' cannot be written to");
            }
        } else {
            File parent = file.getParentFile();
            if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Directory '" + parent + "' could not be created");
            }
        }

        return new FileOutputStream(file, append);
    }
}
