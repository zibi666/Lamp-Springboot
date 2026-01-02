package com.example.edog.controller;

import com.example.edog.service.WebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/voice")
public class VoiceController {

    @Autowired
    private WebSocketServer webSocketServer;

    @PostMapping("/volume")
    public String controlVolume(@RequestParam("command") Integer command,
                                @RequestParam(value = "value", required = false) Integer value) {
        if (command == null) return "Missing command";

        String message;
        if (command == 1) {
            int step = value == null ? 10 : value;
            if (step < 0) step = 0;
            message = "(volume_up," + step + ")";
        } else if (command == 2) {
            int step = value == null ? 10 : value;
            if (step < 0) step = 0;
            message = "(volume_down," + step + ")";
        } else if (command == 3) {
            if (value == null) return "Missing volume value";
            int target = clampVolume(value);
            message = "(volume_set," + target + ")";
        } else {
            return "Unknown volume command";
        }

        webSocketServer.broadcastControlMessage(message);
        return "Sent: " + message;
    }

    @PostMapping("/getvolume")
    public String getVolume() {
        return String.valueOf(webSocketServer.getLatestVolume());
    }

    private int clampVolume(int volume) {
        if (volume < 0) return 0;
        if (volume > 100) return 100;
        return volume;
    }
}
