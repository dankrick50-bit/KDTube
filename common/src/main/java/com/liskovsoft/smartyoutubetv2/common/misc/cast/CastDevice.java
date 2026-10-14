package com.liskovsoft.smartyoutubetv2.common.misc.cast;

/**
 * A screen that is able to play YouTube videos on request: a TV, a stick, a console etc
 */
public class CastDevice {
    public String name;
    /**
     * The id of the screen in the YouTube remote control service ("lounge"). Null: not known yet (see {@link #appUrl}).
     */
    public String screenId;
    /**
     * The address of the YouTube app of a device that is found in the local network (DIAL), null otherwise
     */
    public String appUrl;
    /**
     * The address of a device of the local network that is reached the Chromecast way (Google TV, Android TV...), null otherwise.
     * See {@link CastChannel}
     */
    public String castHost;

    public CastDevice() {
    }

    public CastDevice(String name, String screenId, String appUrl) {
        this.name = name;
        this.screenId = screenId;
        this.appUrl = appUrl;
    }

    public String getTitle() {
        return name != null && !name.isEmpty() ? name : "TV";
    }
}
