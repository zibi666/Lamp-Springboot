package com.example.edog.controller;

import com.example.edog.service.WebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/light")
public class LightController {

    @Autowired
    private WebSocketServer webSocketServer;

    @PostMapping("/brightness")
    public String controlBrightness(@RequestParam("command") Integer command, 
                                    @RequestParam(value = "value", required = false, defaultValue = "10") Integer value) {
        String message = "";
        if (command == 1) {
            // 根据推测，1 应该是调亮，虽然用户描述写了两个 down，但为了逻辑完备性，这里处理为 up
            message = "(brightness_up," + value + ")";
        } else if (command == 2) {
            message = "(brightness_down," + value + ")";
        } else {
            return "Unknown brightness command";
        }
        
        webSocketServer.broadcastControlMessage(message);
        return "Sent: " + message;
    }

    @PostMapping("/temperature")
    public String controlTemperature(@RequestParam("command") Integer command,
                                     @RequestParam(value = "value", required = false, defaultValue = "10") Integer value) {
        String message = "";
        if (command == 3) {
            message = "(tem_up," + value + ")";
        } else if (command == 4) {
            message = "(tem_down," + value + ")";
        } else {
            return "Unknown temperature command";
        }

        webSocketServer.broadcastControlMessage(message);
        return "Sent: " + message;
    }
}