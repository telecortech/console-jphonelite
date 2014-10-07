package ru.telecortech.jphonelite; /**
 * ru.Settings.java
 *
 * Created on Mar 22, 2010, 6:03 PM
 *
 * @author pquiring
 * Copyright (C) 2010-2014 Peter Quiring
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
import java.io.*;
import java.util.*;

import javaforce.*;
import javaforce.voip.*;


public class Settings {
    public static Settings current;
    public static class Line {
        public int same;  //0-5 (-1=disabled) (ignored for lines[0])
        public String user, auth, pass, host;
        public boolean srtp, dtls;
        public int transport;
        public Line() {
            same = 0;
            user = "";
            auth = "";
            pass = "";
            host = "";
            srtp = false;
            dtls = false;
            transport = 0;  //0=UDP 1=TCP 2=TLS
        }
    }
    public Line lines[];
    public String audioInput = "<default>", audioOutput = "<default>";
    public boolean keepAudioOpen = true;
    public String downloadPath = JF.getUserPath() + "/Downloads";
    public boolean usePublish = false;
    public boolean speakerMode = false;
    public int speakerThreshold = 1000;  //0-32k
    public int speakerDelay = 250;  //ms
//    public String audioCodecs = "G729,PCMU";
    public String audioCodecs = "PCMU,PCMA";
    public boolean reinvite = true;  //reinvite when returned multiple codecs
    public int sampleRate = 8000;  //8000, 32000, 44100
    public int interpolation = I_LINEAR;
    public boolean autohold = false;  //auto hold/unhold when switching between active lines
    public int volPlaySW = 75, volRecSW = 75;  //playback / recording vol levels (software)
    public int volPlayHW = 100, volRecHW = 100;  //playback / recording vol levels (hardware) (obsolete - removed in 1.1.0)
    public boolean nativeSound = false, nativeVideo = false;
    public int sipexpires = 3600;  //in seconds : min=300 (5mins) max=3600 (1hr)
    public boolean sipRange = false;  //specify SIP port range (min 32 ports)
    public int sipmin = 5061, sipmax = 5199;
    public boolean rtpRange = false;  //specify RTP port range (min 128 ports)
    public int rtpmin = 32768, rtpmax = 65535;
    public int nat = 0;  //None by default
    public boolean natPrivate = false;
    public String natHost = "", natUser = "", natPass = "";
    public boolean rport = true;
    public boolean received = true;
    public String inRingtone = "*RING", outRingtone = "*NA";

    //static = do not save these settings
    public static boolean aa;
    public static boolean ac;
    public static boolean isLinux = false;
    public static boolean isWindows = false;
    public static boolean isJavaScript = false;

    public final static int I_LINEAR = 0;  //linear interpolation (sounds bad)
    public final static int I_FILTER_CAP = 1;  //capacitor type filter ???

    private void initLines() {
        lines = new Line[6];
        for(int a=0;a<6;a++) lines[a] = new Line();
    }

    public static void loadSettings() {
        try {
            current = new Settings();
            current.lines = new Line[6];
            for (int i=0;i<6;i++) {
                current.lines[i] = new Line();
            }

            if (current.lines == null || current.lines.length != 6) throw new Exception("invalid config");

            //force settings
            current.sampleRate = 8000;  //other rates not working yet (experimental)
            current.interpolation = Settings.I_LINEAR;  //not working yet
            if (!current.hasAudioCodec(RTP.CODEC_G711a)
                    && !current.hasAudioCodec(RTP.CODEC_G711u)
                    && !current.hasAudioCodec(RTP.CODEC_G729a)
                    )
            {
                current.audioCodecs = "G729,PCMU";
            }
            if (current.sipexpires < 300) current.sipexpires = 300;
            if (current.sipexpires > 3600) current.sipexpires = 3600;
            if (current.nat == 1) current.nat = 0;  //beta value (dyndns dropped)

            JFLog.log("loadSettings ok");
        } catch (FileNotFoundException e) {
            JFLog.log("Config file does not exist, using default values.");
            current = new Settings();
            current.initLines();
        } catch (Exception e) {
            JFLog.log(e);
            current = new Settings();
            current.initLines();
        }
    }

    /** Encodes a password with some simple steps. */
    public static String encodePassword(String password) {
        char ca[] = password.toCharArray();
        int sl = ca.length;
        if (sl == 0) return "";
        char tmp;
        for(int p=0;p<sl/2;p++) {
            tmp = ca[p];
            ca[p] = ca[sl-p-1];
            ca[sl-p-1] = tmp;
        }
        StringBuffer out = new StringBuffer();
        for(int p=0;p<sl;p++) {
            ca[p] ^= 0xaa;
            if (ca[p] < 0x10) out.append("0");
            out.append(Integer.toString(ca[p], 16));
        }
//System.out.println("e1=" + out.toString());
        Random r = new Random();
        char key = (char)(r.nextInt(0xef) + 0x10);
        char outkey = key;
        ca = out.toString().toCharArray();
        sl = ca.length;
        for(int p=0;p<sl;p++) {
            ca[p] ^= key;
            key ^= ca[p];
        }
        out = new StringBuffer();
        for(int a=0;a<4;a++) {
            out.append(Integer.toString(r.nextInt(0xef) + 0x10, 16));
        }
        out.append(Integer.toString(outkey, 16));
        for(int p=0;p<sl;p++) {
            if (ca[p] < 0x10) out.append("0");
            out.append(Integer.toString(ca[p], 16));
        }
        for(int a=0;a<4;a++) {
            out.append(Integer.toString(r.nextInt(0xef) + 0x10, 16));
        }
        return out.toString();
    }
    /** Encodes a password. */
    public static String encodePassword(char password[]) {
        return encodePassword(new String(password));
    }
    /** Decodes a password. */
    public static String decodePassword(String crypto) {
        int sl = crypto.length();
        if (sl < 10) return null;
        char key = (char)(int)Integer.valueOf(crypto.substring(8,10), 16);
        char newkey;
        crypto = crypto.substring(10, sl - 8);
        int cl = (sl - 18) / 2;
        char ca[] = new char[cl];
        for(int p=0;p<cl;p++) {
            ca[p] = (char)(int)Integer.valueOf(crypto.substring(p*2, p*2+2), 16);
            newkey = (char)(key ^ ca[p]);
            ca[p] ^= key;
            key = newkey;
        }
        crypto = new String(ca);
        cl = crypto.length() / 2;
        ca = new char[cl];
        for(int p=0;p<cl;p++) {
            ca[p] = (char)(int)Integer.valueOf(crypto.substring(p*2, p*2+2), 16);
        }
        for(int p=0;p<cl;p++) {
            ca[p] ^= 0xaa;
        }
        char tmp;
        for(int p=0;p<cl/2;p++) {
            tmp = ca[p];
            ca[p] = ca[cl-p-1];
            ca[cl-p-1] = tmp;
        }
        return new String(ca);
    }
    public static String getPassword(String pass) {
        if (pass.startsWith("crypto(") && pass.endsWith(")")) {
            if (pass.charAt(8) != ',') return "";  //bad function
            if (pass.charAt(7) != '1') return "";  //unknown crypto type
            try {
                return decodePassword(pass.substring(9, pass.length() - 1));
            } catch (Exception e) {}
        } else {
            return pass;
        }
        return "";
    }
    public boolean hasAudioCodec(Codec codec) {
        String codecs[] = audioCodecs.split(",");
        if (codecs == null) return false;
        for(int a=0;a<codecs.length;a++) {
            if (codecs[a].equals(codec.name)) return true;
        }
        return false;
    }
    public String[] getAudioCodecs() {
        return audioCodecs.split(",");
    }
    private static final int CX = 320;
    private static final int CY = 240;

}