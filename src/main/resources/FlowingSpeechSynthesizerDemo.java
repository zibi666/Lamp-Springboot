package com.alibaba.nls.client;

import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.OutputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.tts.FlowingSpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.FlowingSpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.FlowingSpeechSynthesizerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 此示例演示了：
 *      流式文本语音合成API调用。
 */
public class FlowingSpeechSynthesizerDemo {
    private static final Logger logger = LoggerFactory.getLogger(FlowingSpeechSynthesizerDemo.class);
    private static long startTime;
    private String appKey;
    NlsClient client;
    public FlowingSpeechSynthesizerDemo(String appKey, String token, String url) {
        this.appKey = appKey;
        //创建NlsClient实例应用全局创建一个即可。生命周期可和整个应用保持一致，默认服务地址为阿里云线上服务地址。
        if(url.isEmpty()) {
            client = new NlsClient(token);
        } else {
            client = new NlsClient(url, token);
        }
    }
    private static FlowingSpeechSynthesizerListener getSynthesizerListener() {
        FlowingSpeechSynthesizerListener listener = null;
        try {
            listener = new FlowingSpeechSynthesizerListener() {
                File f=new File("flowingTts.wav");
                FileOutputStream fout = new FileOutputStream(f);
                private boolean firstRecvBinary = true;

                //流式文本语音合成开始
                public void onSynthesisStart(FlowingSpeechSynthesizerResponse response) {
                    System.out.println("name: " + response.getName() +
                                       ", status: " + response.getStatus());
                }
                //服务端检测到了一句话的开始
                public void onSentenceBegin(FlowingSpeechSynthesizerResponse response) {
                    System.out.println("name: " + response.getName() +
                                       ", status: " + response.getStatus());
                    System.out.println("Sentence Begin");
                }
                //服务端检测到了一句话的结束，获得这句话的起止位置和所有时间戳
                public void onSentenceEnd(FlowingSpeechSynthesizerResponse response) {
                    System.out.println("name: " + response.getName() +
                                       ", status: " + response.getStatus() + ", subtitles: " + response.getObject("subtitles"));

                }
                //流式文本语音合成结束
                @Override
                public void onSynthesisComplete(FlowingSpeechSynthesizerResponse response) {
                    // 调用onSynthesisComplete时，表示所有TTS数据已经接收完成，所有文本都已经合成音频并返回。
                    System.out.println("name: " + response.getName() + ", status: " + response.getStatus()+", output file :"+f.getAbsolutePath());
                }
                //收到语音合成的语音二进制数据
                @Override
                public void onAudioData(ByteBuffer message) {
                    try {
                        if(firstRecvBinary) {
                            // 此处计算首包语音流的延迟，收到第一包语音流时，即可以进行语音播放，以提升响应速度（特别是实时交互场景下）。
                            firstRecvBinary = false;
                            long now = System.currentTimeMillis();
                            logger.info("tts first latency : " + (now - FlowingSpeechSynthesizerDemo.startTime) + " ms");
                        }
                        byte[] bytesArray = new byte[message.remaining()];
                        message.get(bytesArray, 0, bytesArray.length);
                        System.out.println("write array:" + bytesArray.length);
                        fout.write(bytesArray);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                //收到语音合成的增量音频时间戳
                @Override
                public void onSentenceSynthesis(FlowingSpeechSynthesizerResponse response) {
                    System.out.println("name: " + response.getName() +
                            ", status: " + response.getStatus() + ", subtitles: " + response.getObject("subtitles"));
                }
                @Override
                public void onFail(FlowingSpeechSynthesizerResponse response){
                    // task_id是调用方和服务端通信的唯一标识，当遇到问题时，需要提供此task_id以便排查。
                    System.out.println(
                            "session_id: " + getFlowingSpeechSynthesizer().getCurrentSessionId() +
                                    ", task_id: " + response.getTaskId() +
                                    //状态码
                                    ", status: " + response.getStatus() +
                                    //错误信息
                                    ", status_text: " + response.getStatusText());
                }
            };
        } catch (Exception e) {
            e.printStackTrace();
        }
        return listener;
    }
    public void process(String[] textArray) {
        FlowingSpeechSynthesizer synthesizer = null;
        try {
            //创建实例，建立连接。
            synthesizer = new FlowingSpeechSynthesizer(client, getSynthesizerListener());
            synthesizer.setAppKey(appKey);
            //设置返回音频的编码格式。
            synthesizer.setFormat(OutputFormatEnum.WAV);
            //设置返回音频的采样率。
            synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
            //发音人。
            synthesizer.setVoice("siyue");
            //音量，范围是0~100，可选，默认50。
            synthesizer.setVolume(50);
            //语调，范围是-500~500，可选，默认是0。
            synthesizer.setPitchRate(0);
            //语速，范围是-500~500，默认是0。
            synthesizer.setSpeechRate(0);
            //此方法将以上参数设置序列化为JSON发送给服务端，并等待服务端确认。
            long start = System.currentTimeMillis();
            synthesizer.start();
            logger.info("tts start latency " + (System.currentTimeMillis() - start) + " ms");
            FlowingSpeechSynthesizerDemo.startTime = System.currentTimeMillis();
            //设置连续两次发送文本的最小时间间隔（毫秒），如果当前调用send时距离上次调用时间小于此值，则会阻塞并等待直到满足条件再发送文本
            synthesizer.setMinSendIntervalMS(100);
            for(String text :textArray) {
                //发送流式文本数据。
                synthesizer.send(text);
            }
            //通知服务端流式文本数据发送完毕，阻塞等待服务端处理完成。
            synthesizer.stop();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            //关闭连接
            if (null != synthesizer) {
                synthesizer.close();
            }
        }
    }
    public void shutdown() {
        client.shutdown();
    }
    public static void main(String[] args) throws Exception {
        String appKey = "your-api-key";
        String token = "your-token";
        // url取默认值
        String url = "wss://nls-gateway-cn-beijing.aliyuncs.com/ws/v1";
        if (args.length == 2) {
            appKey   = args[0];
            token       = args[1];
        } else if (args.length == 3) {
            appKey   = args[0];
            token       = args[1];
            url      = args[2];
        } else {
            System.err.println("run error, need params(url is optional): " + "<app-key> <token> [url]");
            System.exit(-1);
        }
        String[] textArray = {"百草堂与三", "味书屋 鲁迅 \n我家的后面有一个很", "大的园，相传叫作百草园。现在是早已并屋子一起卖", "给朱文公的子孙了，连那最末次的相见也已经",
                "隔了七八年，其中似乎确凿只有一些野草；但那时却是我的乐园。\n不必说碧绿的菜畦，光滑的石井栏，高大的皂荚树，紫红的桑葚；也不必说鸣蝉在树叶里长吟，肥胖的黄蜂伏在菜花",
                "上，轻捷的叫天子(云雀)忽然从草间直窜向云霄里去了。\n单是周围的短短的泥墙根一带，就有无限趣味。油蛉在这里低唱，蟋蟀们在这里弹琴。翻开断砖来，有时会遇见蜈蚣；还有斑",
                "蝥，倘若用手指按住它的脊梁，便会啪的一声，\n从后窍喷出一阵烟雾。何首乌藤和木莲藤缠络着，木莲有莲房一般的果实，何首乌有臃肿的根。有人说，何首乌根是有像人形的，吃了",
                "便可以成仙，我于是常常拔它起来，牵连不断地拔起来，\n也曾因此弄坏了泥墙，却从来没有见过有一块根像人样! 如果不怕刺，还可以摘到覆盆子，像小珊瑚珠攒成的小球，又酸又甜，",
                "色味都比桑葚要好得远......"};
        FlowingSpeechSynthesizerDemo demo = new FlowingSpeechSynthesizerDemo(appKey, token, url);
        demo.process(textArray);
        demo.shutdown();
    }
}