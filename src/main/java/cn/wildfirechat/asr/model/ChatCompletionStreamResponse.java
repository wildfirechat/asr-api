package cn.wildfirechat.asr.model;

import java.util.List;

public class ChatCompletionStreamResponse {
    public String id;
    public String object;
    public Long created;
    public String model;
    public List<Choice> choices;

    public static class Choice {
        public Integer index;
        public Delta delta;
        public String finish_reason;
    }

    public static class Delta {
        public String role;
        public String content;
    }
}
