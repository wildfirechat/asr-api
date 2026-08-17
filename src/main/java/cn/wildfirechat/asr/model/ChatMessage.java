package cn.wildfirechat.asr.model;

public class ChatMessage {
    public String role;
    public String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
