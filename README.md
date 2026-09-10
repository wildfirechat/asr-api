# 野火ASR服务
野火ASR服务包含3个部分，工作流程如下：接口服务接收客户端请求，把请求中的文件地址下载下来后，再调用野火语音转文本服务，得到文本后再调用大模型添加标点符号和修正错误。

接口服务还提供实时语音识别（语音输入）接口：客户端通过 WebSocket 连接接口服务，接口服务鉴权后把音频实时转发给野火语音转文本服务，并把识别结果推送给客户端。

![](./assets/asr_process.png)

1. ***接口服务***：本项目是接口服务，使用springboot编写；
2. ***语音转文本服务***：是个C++服务，是收费的，需要野火提供；
3. ***大模型服务***：任意大模型服务，只要能够对文本进行处理就行。

## 费用
野火语音转文本服务是收费的，价格是1万块钱，关于野火价格请点击[这里](https://docs.wildfirechat.cn/price/)，可以先用体验版测试，如果测试效果满意，请联系野火购买软件包或者源码。

大模型服务需要自己准备，私有部署的或者公有云的都行。这个是可选的，如果没有也没有问题。

## 依赖
只支持X86_64和ARM64 CPU和Linux系统。因为使用了[ggml](https://github.com/ggml-org/ggml)，一些旧的CPU和旧的Linux系统可能不支持，所以当购买之前先测试一下体验版。

## 启动ASR服务的步骤：
接口服务、语音转文本服务和大模型服务可以部署到一起，也可以分开部署。下面是部署方法：

### 1. 启动wf-voice服务
下载 [野火语音转文本服务体验版](https://gitee.com/wfchat/wf-voice-trail) 中对应操作系统和架构，再下载项目中的model模型文件，软件包解压，得到两个可执行文件，wf-voice是程序文件，ffmpeg是音频转码工具。
```
nohup ./wf-voice -m ./model/wf-voice-small.gguf -p 4 2>&1 &
```
其中命令中```-p```这个参数是指定允许并行的个数，每路需要2核CPU。如果是单独部署，```-p```参数应设置为cpu核数/2。如果是与其他服务部署，则规划好为这个服务分配多少核CPU，参数为CPU/2。

这个服务是分CPU架构的，注意要是用正确架构版本。

启动后，服务监听12435端口（HTTP接口，语音消息转文字使用）和12436端口（WebSocket接口，实时语音识别使用）。

### 2. 准备大模型
可以私有部署大模型，或者购买第三方云服务。如果没有也可以先跳过。

### 3. 创建数据库
接口服务需要使用数据库。部署MySQL数据库，执行如下命令创建asr数据库
```
create database asr;
```

### 4. 启动ASR服务
修改本项目配置文件，配置IM服务、语音转文本服务和大模型服务的地址。默认配置是本机，如果部署在同一台服务器上，只需要把IM服务的管理密钥改成IM服务配置文件中的 `http.admin.secret_key`。如果大模型服务需要认证，还需要配置 `llm.api_key`。

配置文件示例：
```properties
# IM服务的管理地址和管理密钥，用来校验客户端的认证码，详见下面的鉴权说明
im.admin_url=http://127.0.0.1:18080
im.admin_secret=123456

asr.server_url=http://127.0.0.1:12435/inference
asr.stream_server_url=ws://127.0.0.1:12436
asr.use_llm_correct=true

llm.server_url=http://127.0.0.1:11434/api/generate
llm.model_name=xxxx
llm.api_key=sk-xxxxxxxx
```

使用命令```mvn clean package```打包本项目代码，把jar包放到服务器上使用命令执行：
```
java -jar asr-api-0.1.jar
```

### 5. 防火墙
* 语音转文字服务：不需要对外访问，12435和12436端口接收接口服务的请求。防火墙禁止出访；只允许接口服务访问12435和12436端口，其他地址和端口禁止入访。
* 大模型服务：处理好端口的联通性。
* 接口服务：需要访问语音转文字服务的12435和12436端口、大模型服务的11434端口和IM服务的管理端口（默认18080）；需要对外访问能够把语音文件下载下来；需要暴露8200给客户端调用。1，出访语音转文字服务的12435和12436端口，出访大模型服务的11434端口，出访IM服务的管理端口；2，出访对象存储服务IP和端口（如果链接可能是任意地址，那就是需要出访全部开放了）；3，放开8200的入访。

如果部署在同一台服务器上：1，出访对象存储服务IP和端口（如果链接可能是任意地址，那就是需要出访全部开放了）; 2，放开8200的入访。

### 6. 使用命令测试：
在接口服务所在服务器上使用命令测试。命令行没有认证码，测试前先在配置文件中设置 `server.need_auth=false` 关闭鉴权，测试完记得改回来：
```
curl -X POST -H "Content-Type: application/json" -d '{"url":"https://media.wfcoss.cn/firechat/222.m4a", "noReuse":true}' http://127.0.0.1:8200/api/recognize
```

## 鉴权
客户端调用 `/api` 下的接口（`/api/hello` 除外）时，需要在 HTTP header `authCode` 中带上认证码，否则返回401。认证码由客户端调用IM SDK的 `getAuthCode` 方法获取，应用类型为管理后台（type 为 2），例如 Android 端：
```java
ChatManager.Instance().getAuthCode("admin", 2, Config.IM_SERVER_HOST, callback);
```
认证码1分钟内有效，客户端每次请求前重新获取。接口服务收到请求后，调用IM服务的管理接口校验认证码，得到用户ID，所以需要正确配置IM服务的管理地址和管理密钥（`im.admin_url`、`im.admin_secret`）。

升级客户端期间，可以设置 `server.need_auth=false` 临时关闭鉴权，兼容还没有带认证码的旧版本客户端。

## 接口
### 1. 语音消息转文字
```
curl -X POST -H "Content-Type: application/json" -H "authCode: 客户端获取的认证码" -d '{"url":"https://media.wfcoss.cn/firechat/222.m4a", "noReuse":true}' http://127.0.0.1:8200/api/recognize
```
url为语音文件链接，可以是常见语音格式；noReuse不复用之前的结果，当为false时，如果有历史请求，会立即返回历史结果。上线时noReuse要为false；测试时注意设置合适的参数。

### 2. 实时语音识别
WebSocket 地址是 `ws://127.0.0.1:8200/api/stream`，建立连接时同样需要在 HTTP header `authCode` 中带上认证码。消息协议与语音转文本服务的 WebSocket 接口相同：
1. 连接后发送的第一条文本消息是 clientId。接口服务不转发这条消息，而是为每个连接生成唯一的 clientId 发给语音转文本服务；
2. 需要边说边出字时，接着发送文本消息 `partial`；
3. 二进制消息发送 16kHz、16-bit、单声道 PCM 音频，建议每条 30 毫秒左右，单条不能超过 64KB；
4. 每识别完一句，推送一条文本消息 `[段开始毫秒时间戳+时长秒] 识别文本`。发送过 `partial` 时，说话过程中还会推送正在说的这句的中间结果 `[PARTIAL] 识别文本`；
5. 说话结束时发送文本消息 `eos`，推送完剩余识别结果后回复 `[EOS]`，客户端收到后关闭连接。

`partial` 和 `eos` 需要支持这两个指令的新版本语音转文本服务。语音转文本服务连不上或者断开时，接口服务以 1011 关闭客户端连接。

## 反向代理
这是个HTTP的服务，有可能使用反向代理或者负载均衡。为了尽快展现给用户，响应是流式返回的，如果使用NG等反向代理或者负载均衡等，需要关闭掉缓存。在Nginx配置中添加:
```nginx
proxy_buffering off;
```
在其他反向代理或者负载均衡请查找手册关闭缓存功能。

实时语音识别使用 WebSocket，反向代理还需要支持 WebSocket 协议升级，Nginx 配置示例：
```nginx
location /api/stream {
    proxy_pass http://127.0.0.1:8200;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 300s;
}
```

## 常见问题
### 1. 性能如何？
A. 语音转文本服务性能非常好，2核心CPU每秒可以处理5-10秒语音，使用CPU即可。

### 2. 是否可以不用大模型？
A. 语音转文本的效果也算可以，但没有标点符号和断句，如果只要语音转文本服务，性能会非常好。可以在配置文件中关掉大模型，或者请求参数不使用大模型。但建议还是用上大模型，做一些简单的校正和添加标点符号。

### 3. 有时发现特别慢？
A. 我们发现当CPU出现竞争时会特别慢。注意配置路数，避免超过CPU总数。

### 4. 测试发现等待较长时间，然后一次出现响应，不像野火测试服务一小段一小段返回？
A. 这有可能网络中间环节有缓存。请在接口服务器上，使用```curl -X POST -H "Content-Type: application/json" -d '{"url":"https://media.wfcoss.cn/firechat/222.m4a", "noReuse":true}' http://127.0.0.1:8200/api/recognize``` 测试看看是不是一小段一小段的响应。确认服务正常后，再去查找是中间那个环节出了问题。如果有nginx或者其他网络中间件，请检查是否没有关闭了缓存。

### 5. 如何避免被盗用？
A. 默认开启鉴权（`server.need_auth=true`），客户端需要带上从IM服务获取的认证码，只有IM服务的用户才能调用，详见上面的鉴权说明。

### 6. 如何处理统计数据
A. 本项目把每个语音消息转文字请求（包括请求的用户ID）都记录到了数据库中，可以二开来统计转换的各种信息。实时语音识别只打印日志，没有记录到数据库。
