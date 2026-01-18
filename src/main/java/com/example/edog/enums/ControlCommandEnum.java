package com.example.edog.enums;

import java.util.regex.Pattern;

public enum ControlCommandEnum {
    
    LIGHT_OFF("关灯", "(brightness_down,100)", "好的"),
    LIGHT_ON("开灯", "(brightness_up,50)", "好的"),
    BRIGHTNESS_DOWN_SMALL("调低亮度", "(brightness_down,10)", "好的，已将灯光亮度调低10%"),
    BRIGHTNESS_UP_SMALL("调高亮度", "(brightness_up,10)", "好的，已将灯光亮度调高10%"),
    TEMP_UP_SMALL("调高色温", "(tem_up,10)", "好的，已将灯光色温调高10%"),
    TEMP_DOWN_SMALL("调低色温", "(tem_down,10)", "好的，已将灯光色温调低10%"),
    
    VOLUME_UP("音量调高", "(volume_up,15)", "好的，音量已调高"),
    VOLUME_DOWN("音量调低", "(volume_down,15)", "好的，音量已调低"),
    
    BRIGHTNESS_UP("亮度调高", "(brightness_up,15)", "好的，亮度已调高"),
    BRIGHTNESS_DOWN("亮度调低", "(brightness_down,15)", "好的，亮度已调低"),
    
    TEMP_UP("色温调暖", "(tem_up,15)", "好的，色温已调暖"),
    TEMP_DOWN("色温调冷", "(tem_down,15)", "好的，色温已调冷");

    private final String triggerWord;
    private final String commandTemplate;
    private final String confirmText;

    ControlCommandEnum(String triggerWord, String commandTemplate, String confirmText) {
        this.triggerWord = triggerWord;
        this.commandTemplate = commandTemplate;
        this.confirmText = confirmText;
    }

    public static ControlCommandEnum match(String text) {
        if (text == null || text.isEmpty()) return null;
        // 去除常见标点符号，确保完全匹配
        String cleanText = text.replaceAll("[。，、？！.?,!]", "").trim();
        for (ControlCommandEnum command : values()) {
            if (command.triggerWord.equals(cleanText)) {
                return command;
            }
        }
        return null;
    }

    public String getCommand() {
        return commandTemplate;
    }

    public String getConfirmText() {
        return confirmText;
    }
}
