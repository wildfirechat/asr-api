package cn.wildfirechat.asr.model;

import java.util.List;

public class ChatCompletionRequest {
    public String model;
    public List<ChatMessage> messages;
    public boolean stream;
    public Float temperature;

    public ChatCompletionRequest(String model, List<ChatMessage> messages) {
        this.model = model;
        this.messages = messages;
        this.stream = true;
    }
}
