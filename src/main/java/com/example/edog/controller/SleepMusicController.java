package com.example.edog.controller;

import com.example.edog.service.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 助眠音乐控制器
 * 提供给扣子（Coze）调用的接口，用于控制设备播放/停止助眠音乐
 */
@RestController
@RequestMapping("/sleep-music")
public class SleepMusicController {

    private static final Logger log = LoggerFactory.getLogger(SleepMusicController.class);

    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 开始播放助眠音乐
     * 
     * @return 操作结果
     */
    @PostMapping("/start")
    public Map<String, Object> startSleepMusic() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String message = "(sleep_music_start,0)";
            webSocketServer.broadcastControlMessage(message);
            
            log.info("已发送助眠音乐播放命令: {}", message);
            
            result.put("success", true);
            result.put("message", "助眠音乐播放命令已发送");
            result.put("command", message);
        } catch (Exception e) {
            log.error("发送助眠音乐播放命令失败", e);
            result.put("success", false);
            result.put("message", "发送命令失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 停止播放助眠音乐
     * 
     * @return 操作结果
     */
    @PostMapping("/stop")
    public Map<String, Object> stopSleepMusic() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String message = "(sleep_music_stop,0)";
            webSocketServer.broadcastControlMessage(message);
            
            log.info("已发送停止助眠音乐命令: {}", message);
            
            result.put("success", true);
            result.put("message", "停止助眠音乐命令已发送");
            result.put("command", message);
        } catch (Exception e) {
            log.error("发送停止助眠音乐命令失败", e);
            result.put("success", false);
            result.put("message", "发送命令失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 控制助眠音乐（统一接口）
     * 
     * @param action 动作：start（开始播放）或 stop（停止播放）
     * @return 操作结果
     */
    @PostMapping("/control")
    public Map<String, Object> controlSleepMusic(@RequestParam("action") String action) {
        Map<String, Object> result = new HashMap<>();
        
        if (action == null || action.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "缺少action参数");
            return result;
        }
        
        action = action.trim().toLowerCase();
        
        switch (action) {
            case "start":
            case "play":
            case "on":
                return startSleepMusic();
            case "stop":
            case "pause":
            case "off":
                return stopSleepMusic();
            default:
                result.put("success", false);
                result.put("message", "未知的action参数: " + action + "。支持的值: start, stop, play, pause, on, off");
                return result;
        }
    }

    /**
     * GET接口 - 开始播放助眠音乐（便于测试）
     */
    @GetMapping("/start")
    public Map<String, Object> startSleepMusicGet() {
        return startSleepMusic();
    }

    /**
     * GET接口 - 停止播放助眠音乐（便于测试）
     */
    @GetMapping("/stop")
    public Map<String, Object> stopSleepMusicGet() {
        return stopSleepMusic();
    }

    /**
     * 切换歌曲（上一首/下一首）
     * 提供给扣子（Coze）调用的统一接口
     * 
     * @param direction 切换方向：next（下一首）或 prev/previous（上一首）
     * @return 操作结果
     */
    @PostMapping("/switch")
    public Map<String, Object> switchTrack(@RequestParam("direction") String direction) {
        Map<String, Object> result = new HashMap<>();
        
        if (direction == null || direction.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "缺少direction参数");
            return result;
        }
        
        direction = direction.trim().toLowerCase();
        String command;
        String actionDesc;
        
        switch (direction) {
            case "next":
            case "下一首":
                command = "sleep_music_next";
                actionDesc = "下一首";
                break;
            case "prev":
            case "previous":
            case "上一首":
                command = "sleep_music_prev";
                actionDesc = "上一首";
                break;
            default:
                result.put("success", false);
                result.put("message", "未知的direction参数: " + direction + "。支持的值: next, prev, previous, 下一首, 上一首");
                return result;
        }
        
        try {
            String message = "(" + command + ",0)";
            webSocketServer.broadcastControlMessage(message);
            
            log.info("已发送切换歌曲命令: {}", message);
            
            result.put("success", true);
            result.put("message", actionDesc + "命令已发送");
            result.put("command", message);
            result.put("direction", direction);
        } catch (Exception e) {
            log.error("发送切换歌曲命令失败", e);
            result.put("success", false);
            result.put("message", "发送命令失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * GET接口 - 切换歌曲（便于测试）
     */
    @GetMapping("/switch")
    public Map<String, Object> switchTrackGet(@RequestParam("direction") String direction) {
        return switchTrack(direction);
    }
}
