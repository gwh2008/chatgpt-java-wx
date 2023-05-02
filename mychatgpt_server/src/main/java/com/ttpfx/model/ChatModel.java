package com.ttpfx.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttpfx.vo.chat.ChatMessage;
import com.ttpfx.vo.chat.ChatRequestParameter;
import com.ttpfx.vo.chat.ChatResponseParameter;
import com.ttpfx.vo.chat.Choice;
import org.apache.hc.client5.http.async.methods.AbstractCharResponseConsumer;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.nio.support.AsyncRequestBuilder;
import org.springframework.stereotype.Component;

import javax.websocket.Session;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * @author ttpfx
 * @date 2023/3/28
 */
@Component
public class ChatModel {

    /**
     * 这里分别代表密钥，请求的地址，编码
     */
    private String apiKey="自己的apiKey";
    private String url="https://api.openai.com/v1/chat/completions";
    private Charset charset = StandardCharsets.UTF_8;

    /**
     * 设置异步请求的客户端
     */
    private CloseableHttpAsyncClient asyncClient = HttpAsyncClients.createDefault();
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 该方法会异步请求chatGpt的接口，返回答案
     * @param session webSocket的Session
     * @param chatGptRequestParameter 请求参数
     * @param question 问题
     * @return 返回chatGpt给出的答案
     */
    public String getAnswer(Session session, ChatRequestParameter chatGptRequestParameter, String question) {
        asyncClient.start();
        // 创建一个post请求
        AsyncRequestBuilder asyncRequest = AsyncRequestBuilder.post(url);

        // 设置请求参数
        chatGptRequestParameter.addMessages(new ChatMessage("user", question));

        // 请求的参数转换为字符串
        String valueAsString = null;
        try {
            valueAsString = objectMapper.writeValueAsString(chatGptRequestParameter);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        // 设置编码和请求参数
        ContentType contentType = ContentType.create("text/plain", charset);
        asyncRequest.setEntity(valueAsString, contentType);
        asyncRequest.setCharset(charset);

        // 设置请求头
        asyncRequest.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        // 设置登录凭证
        asyncRequest.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);

        // 下面就是生产者消费者模型
        CountDownLatch latch = new CountDownLatch(1);
        // 用于记录返回的答案
        StringBuilder sb = new StringBuilder();
        // 消费者
        AbstractCharResponseConsumer<HttpResponse> consumer = new AbstractCharResponseConsumer<HttpResponse>() {
            HttpResponse response;

            @Override
            protected void start(HttpResponse response, ContentType contentType) throws HttpException, IOException {
                setCharset(charset);
                this.response = response;
            }

            @Override
            protected int capacityIncrement() {
                return Integer.MAX_VALUE;
            }

            @Override
            protected void data(CharBuffer src, boolean endOfStream) throws IOException {
                // 收到一个请求就进行处理
                String ss = src.toString();
                // 通过data:进行分割，如果不进行此步，可能返回的答案会少一些内容
                for (String s : ss.split("data:")) {
                    // 去除掉data:
                    if (s.startsWith("data:")) {
                        s = s.substring(5);
                    }
                    // 返回的数据可能是（DONE）
                    if (s.length() > 8) {
                        // 转换为对象
                        ChatResponseParameter responseParameter = objectMapper.readValue(s, ChatResponseParameter.class);
                        // 处理结果
                        for (Choice choice : responseParameter.getChoices()) {
                            String content = choice.getDelta().getContent();
                            if (content != null && !"".equals(content)) {
                                // 保存结果
                                sb.append(content);
                                // 将结果使用webSocket传送过去
                                session.getBasicRemote().sendText(content);
                            }
                        }
                    }
                }
            }

            @Override
            protected HttpResponse buildResult() throws IOException {
                return response;
            }

            @Override
            public void releaseResources() {
            }
        };

        // 执行请求
        asyncClient.execute(asyncRequest.build(), consumer, new FutureCallback<HttpResponse>() {

            @Override
            public void completed(HttpResponse response) {
                latch.countDown();
                chatGptRequestParameter.addMessages(new ChatMessage("assistant", sb.toString()));
                System.out.println("回答结束！！！");
            }

            @Override
            public void failed(Exception ex) {
                latch.countDown();
                System.out.println("failed");
                ex.printStackTrace();
            }

            @Override
            public void cancelled() {
                latch.countDown();
                System.out.println("cancelled");
            }

        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        // 返回最终答案，用于保存数据库的
        return sb.toString();
    }


    /**
     * 这个方法和上面一样，用于测试的，可以在控制台打印输出结果
     *
     * @param chatGptRequestParameter 请求的参数
     * @param question                问题
     */
    public void printAnswer(ChatRequestParameter chatGptRequestParameter, String question) {
        asyncClient.start();
        // 创建一个HttpPost
        AsyncRequestBuilder asyncRequest = AsyncRequestBuilder.post(url);
        // 创建一个ObjectMapper，用于解析和创建json

        // 设置请求参数
        chatGptRequestParameter.addMessages(new ChatMessage("user", question));

        String valueAsString = null;
        try {
            valueAsString = objectMapper.writeValueAsString(chatGptRequestParameter);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        System.out.println(valueAsString);
        ContentType contentType = ContentType.create("text/plain", charset);
        asyncRequest.setEntity(valueAsString, contentType);

        asyncRequest.setCharset(charset);
        // 设置请求头
        asyncRequest.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        // 设置登录凭证
        asyncRequest.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);

        CountDownLatch latch = new CountDownLatch(1);

        StringBuilder sb = new StringBuilder();

        AbstractCharResponseConsumer<HttpResponse> consumer = new AbstractCharResponseConsumer<HttpResponse>() {

            HttpResponse response;

            @Override
            protected void start(HttpResponse response, ContentType contentType) throws HttpException, IOException {
                setCharset(charset);
                this.response = response;
            }

            @Override
            protected int capacityIncrement() {
                return Integer.MAX_VALUE;
            }

            @Override
            protected void data(CharBuffer src, boolean endOfStream) throws IOException {
                String ss = src.toString();
                for (String s : ss.split("data:")) {
                    if (s.startsWith("data:")) {
                        s = s.substring(5);
                    }
                    if (s.length() > 10) {
                        ChatResponseParameter responseParameter = objectMapper.readValue(s, ChatResponseParameter.class);
                        for (Choice choice : responseParameter.getChoices()) {
                            String content = choice.getDelta().getContent();
                            if (content != null && !"".equals(content)) {
                                sb.append(content);
                                System.out.print(content);
                            }
                        }
                    }
                }

            }

            @Override
            protected HttpResponse buildResult() throws IOException {
                return response;
            }

            @Override
            public void releaseResources() {
            }
        };

        asyncClient.execute(asyncRequest.build(), consumer, new FutureCallback<HttpResponse>() {

            @Override
            public void completed(HttpResponse response) {
                latch.countDown();
                chatGptRequestParameter.addMessages(new ChatMessage("assistant", sb.toString()));
                System.out.println("回答结束！！！");
            }

            @Override
            public void failed(Exception ex) {
                latch.countDown();
                System.out.println("failed");
                ex.printStackTrace();
            }

            @Override
            public void cancelled() {
                latch.countDown();
                System.out.println("cancelled");
            }

        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
