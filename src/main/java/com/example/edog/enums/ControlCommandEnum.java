package com.example.edog.enums;

import java.util.regex.Pattern;

public enum ControlCommandEnum {
    
    LIGHT_OFF("关灯", Pattern.compile("关灯"), "(brightness_down,100)", "好的"),
    LIGHT_ON("开灯", Pattern.compile("开灯"), "(brightness_up,50)", "好的"),
    BRIGHTNESS_DOWN_SMALL("调低亮度", Pattern.compile("调低亮度"), "(brightness_down,10)", "好的，已将灯光亮度调低10%"),
    BRIGHTNESS_UP_SMALL("调高亮度", Pattern.compile("调高亮度"), "(brightness_up,10)", "好的，已将灯光亮度调高10%"),
    TEMP_UP_SMALL("调高色温", Pattern.compile("调高色温"), "(tem_up,10)", "好的，已将灯光色温调高10%"),
    TEMP_DOWN_SMALL("调低色温", Pattern.compile("调低色温"), "(tem_down,10)", "好的，已将灯光色温调低10%"),
    
    VOLUME_UP("音量调高", Pattern.compile("(调高|提高|增大|加大|升高|大一?点|大声|音量.*[加增高大升]|volume.*up|louder|increase.*volume)", Pattern.CASE_INSENSITIVE), "(volume_up,15)", "好的，音量已调高"),
    VOLUME_DOWN("音量调低", Pattern.compile("(调低|降低|减小|减少|小一?点|小声|音量.*[减降低小]|volume.*down|quieter|decrease.*volume)", Pattern.CASE_INSENSITIVE), "(volume_down,15)", "好的，音量已调低"),
    
    BRIGHTNESS_UP("亮度调高", Pattern.compile("(调亮|提亮|增亮|亮一?点|亮度.*[加增高大升亮]|brighter|brightness.*up)", Pattern.CASE_INSENSITIVE), "(brightness_up,15)", "好的，亮度已调高"),
    BRIGHTNESS_DOWN("亮度调低", Pattern.compile("(调暗|降暗|暗一?点|亮度.*[减降低小暗]|dimmer|brightness.*down)", Pattern.CASE_INSENSITIVE), "(brightness_down,15)", "好的，亮度已调低"),
    
    TEMP_UP("色温调暖", Pattern.compile("(调暖|变暖|暖一?点|暖色|色温.*[加增高大升暖]|warmer|temperature.*up)", Pattern.CASE_INSENSITIVE), "(tem_up,15)", "好的，色温已调暖"),
    TEMP_DOWN("色温调冷", Pattern.compile("(调冷|变冷|冷一?点|冷色|色温.*[减降低小冷]|cooler|temperature.*down)", Pattern.CASE_INSENSITIVE), "(tem_down,15)", "好的，色温已调冷");

    private final String description;
    private final Pattern pattern;
    private final String commandTemplate;
    private final String confirmText;

    ControlCommandEnum(String description, Pattern pattern, String commandTemplate, String confirmText) {
        this.description = description;
        this.pattern = pattern;
        this.commandTemplate = commandTemplate;
        this.confirmText = confirmText;
    }

    public static ControlCommandEnum match(String text) {
        if (text == null || text.isEmpty()) return null;
        for (ControlCommandEnum command : values()) {
            if (command.pattern.matcher(text).find()) {
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
