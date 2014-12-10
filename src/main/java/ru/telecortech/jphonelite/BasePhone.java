package ru.telecortech.jphonelite;

import javaforce.JF;
import javaforce.JFLog;
import javaforce.XML;
import javaforce.voip.*;

import java.io.ByteArrayInputStream;
import java.util.Random;
import java.util.Vector;

/**
 *
 * ru.BasePhone.java
 *
 * Created on Oct 22, 2011, 10:26:03 AM
 *
 * @author pquiring@gmail.com
 *
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

public class BasePhone implements SIPClientInterface, RTPInterface {

    //private
    private EventListener eventListener;

    //global data
    public int sipmin = 5061;
    public int sipmax = 5199;
    public int sipnext = 5061;
    public int line = -1;  //current selected line (0-5) (-1=none)
    public PhoneLine lines[];
    public Sound sound = new Sound();
    public String lastDial;
    public java.util.Timer timerKeepAlive, timerRegisterExpires, timerRegisterRetries;
    public int registerRetries;
    public Vector<String> monitorList = new Vector<String>();
    public boolean active = true;

    public boolean registeringAll = false;
    public boolean doConfig = false;  //do config after register all

    /**
     * Creates new form jPhonePanel
     */
    public BasePhone(EventListener eventListener) {
        this.eventListener = eventListener;
    }

    /**
     * Registers all SIP connections.
     */
    public void reRegisterAll() {
        registeringAll = true;
        int idx;
        String host;
        int port = -1;
        for (int a = 0; a < 6; a++) {
            PhoneLine pl = lines[a];
            if ((a > 0) && (Settings.current.lines[a].same != -1)) continue;
            pl.srtp = Settings.current.lines[a].srtp;
            pl.dtls = Settings.current.lines[a].dtls;
            if (Settings.current.lines[a].host.length() == 0) continue;
            if (Settings.current.lines[a].user.length() == 0) continue;
            lines[a].sip = new SIPClient();
            idx = Settings.current.lines[a].host.indexOf(':');
            if (idx == -1) {
                host = Settings.current.lines[a].host;
                switch (Settings.current.lines[a].transport) {
                    case 0:
                    case 1:
                        port = 5060;  //default UDP/TCP port
                        break;
                    case 2:
                        port = 5061;  //default TLS port
                        break;
                }
            } else {
                host = Settings.current.lines[a].host.substring(0, idx);
                port = JF.atoi(Settings.current.lines[a].host.substring(idx + 1));
            }
            switch (Settings.current.lines[a].transport) {
                case 0:
                    pl.transport = SIP.Transport.UDP;
                    break;
                case 1:
                    pl.transport = SIP.Transport.TCP;
                    break;
                case 2:
                    pl.transport = SIP.Transport.TLS;
                    break;
            }
            int attempt = 0;
            while (!pl.sip.init(host, port, getlocalport(), this, pl.transport)) {
                attempt++;
                if (attempt == 10) {
                    pl.sip = null;
                    pl.status = "SIP init failed";
                    break;
                }
            }
            if (pl.sip == null) continue;  //sip.init failed
            pl.user = Settings.current.lines[a].user;
            if ((Settings.current.lines[a].pass == null) || (Settings.current.lines[a].pass.length() == 0) || (Settings.current.lines[a].pass.equals("crypto(1,)"))) {
                pl.auth = true;
                pl.noregister = true;
                pl.status = "Ready (" + pl.user + ")";
            }
        }
        //setup "Same as" lines
        int same;
        for (int a = 1; a < 6; a++) {
            same = Settings.current.lines[a].same;
            if (same == -1) continue;
            PhoneLine pl = lines[a];
            pl.srtp = lines[same].srtp;
            pl.transport = lines[same].transport;
            pl.dtls = lines[same].dtls;
            pl.sip = lines[same].sip;
            pl.user = lines[same].user;
            pl.noregister = lines[same].noregister;
            if (pl.noregister) {
                pl.auth = true;
                pl.status = "Ready (" + pl.user + ")";
            }
        }
        //register lines
        for (int a = 0; a < 6; a++) {
            if ((a > 0) && (Settings.current.lines[a].same != -1)) continue;
            PhoneLine pl = lines[a];
            if (pl.sip == null) continue;
            try {
                pl.sip.register(Settings.current.lines[a].user, Settings.current.lines[a].auth
                        , Settings.getPassword(Settings.current.lines[a].pass), Settings.current.sipexpires);
            } catch (Exception e) {
                JFLog.log(e);
            }
        }
        //setup reRegister timer (expires)
        int expires = Settings.current.sipexpires;
        expires -= 5;  //do a little early just in case
        expires *= 1000;  //convert to ms
        timerRegisterExpires = new java.util.Timer();
        timerRegisterExpires.scheduleAtFixedRate(new ReRegisterExpires(), expires, expires);
        registerRetries = 0;
        timerRegisterRetries = new java.util.Timer();
        timerRegisterRetries.schedule(new ReRegisterRetries(), 1000);
        registeringAll = false;
        if (doConfig) {
            doConfig = false;
        }
    }

    /**
     * Expires registration with all SIP connections.
     */

    public void unRegisterAll() {
        if (timerRegisterExpires != null) {
            timerRegisterExpires.cancel();
            timerRegisterExpires = null;
        }
        if (timerRegisterRetries != null) {
            timerRegisterRetries.cancel();
            timerRegisterRetries = null;
        }
        for (int a = 0; a < 6; a++) {
            PhoneLine pl = lines[a];
            if (pl.incall) {
                selectLine(a);
                if (pl.talking)
                    pl.sip.bye(pl.callid);
                else
                    pl.sip.cancel(pl.callid);
                endLine(a);
            }
            pl.dial = "";
            pl.status = "";
            pl.unauth = false;
            pl.auth = false;
            pl.noregister = false;
            pl.user = "";
            if ((a > 0) && (Settings.current.lines[a].same != -1)) {
                pl.sip = null;
                continue;
            }
            if (pl.sip == null) continue;
            if (pl.sip.isRegistered()) {
                try {
                    pl.sip.unregister();
                } catch (Exception e) {
                    JFLog.log(e);
                }
            }
        }
        int maxwait;
        for (int a = 0; a < 6; a++) {
            PhoneLine pl = lines[a];
            if (pl.sip == null) continue;
            maxwait = 1000;
            while (pl.sip.isRegistered()) {
                JF.sleep(10);
                maxwait -= 10;
                if (maxwait == 0) break;
            }
            pl.sip.uninit();
            pl.sip = null;
        }
    }

    public void register(String phone, String password, String address) {

        Settings.isWindows = JF.isWindows();
        Settings.isLinux = !Settings.isWindows;

        Settings.loadSettings();
        Settings.current.lines[0].same = -1;
        Settings.current.lines[0].user = phone;
        Settings.current.lines[0].auth = phone;
        Settings.current.lines[0].pass = "crypto(1," + Settings.encodePassword(password) + ")";
        Settings.current.lines[0].host = address;

        lines = new PhoneLine[6];
        for (int a = 0; a < 6; a++) {
            lines[a] = new PhoneLine();
        }

        keepAliveinit();
        reset();
    }

    /**
     * Starts a call or accepts an inbound call on selected line.
     * 1-accept, 2-invite
     */

    public void call(int action, String phone) {

        if (line == -1) return;
        if (action == 2)
            lines[line].dial = phone;
        PhoneLine pl = lines[line];
        if (pl.sip == null) return;
        if (!pl.auth) return;
        if (pl.incall) {
            JFLog.log("already in call");
            return;
        }
        if (pl.dial.length() == 0) {
            JFLog.log("no number");
            return;
        }
        if (pl.incoming) {
            callAccept();
        } else {
            callInvite();
        }
        if (Settings.current.ac) {
            if (!pl.cnf) conference();
        }
    }

    /**
     * Terminates a call.
     */

    public void end() {
        if (line == -1) return;
        PhoneLine pl = lines[line];
        if (pl.incoming) {
            pl.sip.deny(pl.callid, "IGNORE", 486);
            pl.incoming = false;
            pl.ringing = false;
            pl.ringback = false;
            pl.dial = "";
            pl.status = "Hungup";
            return;
        }
        pl.dial = "";
        if (!pl.incall) {
            //no call (update status)
            if ((pl.sip != null) && (!pl.unauth)) pl.status = "Ready (" + pl.user + ")";
            return;
        }
        if (pl.talking)
            pl.sip.bye(pl.callid);
        else
            pl.sip.cancel(pl.callid);
        endLine(line);
    }

    /**
     * Cleanup after a call is terminated (call terminated local or remote).
     */

    public void endLine(int xline) {
        System.out.println("-----REJECTED");
        eventListener.rejected();
        PhoneLine pl = lines[xline];
        pl.dial = "";
        pl.status = "Hungup";
        pl.trying = false;
        pl.ringing = false;
        pl.ringback = false;
        pl.incoming = false;
        pl.cnf = false;
        pl.xfer = false;
        pl.incall = false;
        pl.talking = false;
        pl.hld = false;
        if (pl.audioRTP != null) {
            pl.audioRTP.stop();
            pl.audioRTP = null;
        }
        pl.callid = "";
    }

    /**
     * Starts a outbound call.
     */

    public void callInvite() {
        PhoneLine pl = lines[line];
        pl.to = pl.dial;
        pl.incall = true;
        pl.trying = false;
        pl.ringing = false;
        pl.ringback = false;
        pl.talking = false;
        pl.incoming = false;
        pl.status = "Dialing";
        lastDial = pl.dial;
        //ru.Settings.addCallLog(pl.dial);
        pl.audioRTP = new RTP();
        if (!pl.audioRTP.init(this)) {
            endLine(line);
            pl.dial = "";
            pl.status = "RTP init failed";
            return;
        }
        pl.localsdp = getLocalSDPInvite(pl);

        pl.callid = pl.sip.invite(pl.dial, pl.localsdp);
        if ((Settings.current.usePublish)) lines[line].sip.publish("busy");
        callInviteUpdate();
    }

    /**
     * Returns the SDP Stream complementary mode (send <-> receive)
     */
    private SDP.Mode complementMode(SDP.Mode mode) {

        switch (mode) {
            case recvonly:
                return SDP.Mode.sendonly;
            case sendonly:
                return SDP.Mode.recvonly;
            case inactive:  //no break
            case sendrecv:
                return mode;
        }
        return null;
    }

    /**
     * Returns SDP that matches requested SDP.
     */

    private SDP getLocalSDPAccept(PhoneLine pl) {
        SDP sdp = new SDP();
        SDP.Stream astream = pl.sdp.getFirstAudioStream();
        SDP.Stream newAstream = sdp.addStream(SDP.Type.audio);
        newAstream.port = pl.audioRTP.getlocalrtpport();
        newAstream.mode = complementMode(astream.mode);
        if (pl.srtp) {
            newAstream.profile = SDP.Profile.SAVP;
            if (astream.keyExchange == SDP.KeyExchange.SDP) {
                newAstream.keyExchange = SDP.KeyExchange.SDP;
                newAstream.addKey("AES_CM_128_HMAC_SHA1_80", genKey(), genSalt());
            } else {
                newAstream.keyExchange = SDP.KeyExchange.DTLS;
                newAstream.sdp.fingerprint = fingerprintSHA256;
                newAstream.sdp.iceufrag = RTP.genIceufrag();
                newAstream.sdp.icepwd = RTP.genIcepwd();
            }
        }
        String enabledCodecs[] = Settings.current.getAudioCodecs();
        for (int a = 0; a < enabledCodecs.length; a++) {
            if ((enabledCodecs[a].equals(RTP.CODEC_G729a.name)) && (astream.hasCodec(RTP.CODEC_G729a))) {
                newAstream.addCodec(RTP.CODEC_G729a);
                break;
            }
            if ((enabledCodecs[a].equals(RTP.CODEC_G711u.name)) && (astream.hasCodec(RTP.CODEC_G711u))) {
                newAstream.addCodec(RTP.CODEC_G711u);
                break;
            }
            if ((enabledCodecs[a].equals(RTP.CODEC_G711a.name)) && (astream.hasCodec(RTP.CODEC_G711a))) {
                newAstream.addCodec(RTP.CODEC_G711a);
                break;
            }
        }
        return sdp;
    }

    /**
     * Returns SDP packet for all enabled codecs (audio and video).
     */

    private byte[] genKey() {
        byte ret[] = new byte[16];
        new Random().nextBytes(ret);
        return ret;
    }

    private byte[] genSalt() {
        byte ret[] = new byte[14];
        new Random().nextBytes(ret);
        return ret;
    }

    private SDP getLocalSDPInvite(PhoneLine pl) {
        SDP sdp = new SDP();
        SDP.Stream stream = sdp.addStream(SDP.Type.audio);
        stream.content = "audio1";
        stream.port = pl.audioRTP.getlocalrtpport();
        if (pl.srtp) {
            stream.profile = SDP.Profile.SAVP;
            if (!pl.dtls) {
                stream.keyExchange = SDP.KeyExchange.SDP;
                stream.addKey("AES_CM_128_HMAC_SHA1_80", genKey(), genSalt());
            } else {
                stream.keyExchange = SDP.KeyExchange.DTLS;
                stream.sdp.fingerprint = fingerprintSHA256;
                stream.sdp.iceufrag = RTP.genIceufrag();
                stream.sdp.icepwd = RTP.genIcepwd();
            }
        }
        String enabledCodecs[] = Settings.current.getAudioCodecs();
        for (int a = 0; a < enabledCodecs.length; a++) {
            if (enabledCodecs[a].equals(RTP.CODEC_G729a.name)) stream.addCodec(RTP.CODEC_G729a);
            if (enabledCodecs[a].equals(RTP.CODEC_G711u.name)) stream.addCodec(RTP.CODEC_G711u);
            if (enabledCodecs[a].equals(RTP.CODEC_G711a.name)) stream.addCodec(RTP.CODEC_G711a);
        }
        return sdp;
    }

    /**
     * Accepts an inbound call on selected line.
     */
    public void callAccept() {
        PhoneLine pl = lines[line];
        try {
            SDP.Stream astream = pl.sdp.getFirstAudioStream();
            if ((!astream.hasCodec(RTP.CODEC_G729a) || !Settings.current.hasAudioCodec(RTP.CODEC_G729a))
                    && (!astream.hasCodec(RTP.CODEC_G711u) || !Settings.current.hasAudioCodec(RTP.CODEC_G711u))
                    && (!astream.hasCodec(RTP.CODEC_G711a) || !Settings.current.hasAudioCodec(RTP.CODEC_G711a))) {
                JFLog.log("err:callAccept() : No compatible audio codec offered");
                pl.sip.deny(pl.callid, "NO_COMPATIBLE_CODEC", 415);
                onCancel(pl.sip, pl.callid, 415);
                return;
            }
            pl.to = pl.dial;
            pl.audioRTP = new RTP();
            if (!pl.audioRTP.init(this)) {
                throw new Exception("RTP.init() failed");
            }
            pl.localsdp = getLocalSDPAccept(pl);

            astream.setCodec(pl.localsdp.getFirstAudioStream().codecs[0]);

            if (!pl.audioRTP.start()) {
                throw new Exception("RTP.start() failed");
            }
            if (pl.audioRTP.createChannel(astream) == null) {
                throw new Exception("RTP.createChannel() failed");
            }
            if (pl.sdp.getFirstAudioStream().isSecure()) {
                SRTPChannel channel = (SRTPChannel) pl.audioRTP.getDefaultChannel();
                if (pl.sdp.getFirstAudioStream().keyExchange == SDP.KeyExchange.SDP) {
                    SDP.Stream local = pl.localsdp.getFirstAudioStream();
                    SDP.Key localKey = local.getKey("AES_CM_128_HMAC_SHA1_80");
                    if (localKey == null) throw new Exception("Local SRTP keys not found");
                    channel.setLocalKeys(localKey.key, localKey.salt);
                    SDP.Stream remote = pl.sdp.getFirstAudioStream();
                    SDP.Key remoteKey = remote.getKey("AES_CM_128_HMAC_SHA1_80");
                    if (remoteKey == null) throw new Exception("Remote SRTP keys not found");
                    channel.setRemoteKeys(remoteKey.key, remoteKey.salt);
                } else {
                    SDP.Stream local = pl.localsdp.getFirstAudioStream();
                    channel.setDTLS(true, local.sdp.iceufrag, local.sdp.icepwd);
                }
            }
            if (!pl.audioRTP.getDefaultChannel().start()) {
                throw new Exception("RTPChannel.start() failed");
            }
            pl.sip.accept(pl.callid, pl.localsdp);
            pl.incall = true;
            pl.ringing = false;
            pl.ringback = false;
            pl.incoming = false;
            pl.talking = true;
            pl.status = "Connected";
            eventListener.accepted();
        } catch (Exception e) {
            JFLog.log(e);
            pl.sip.deny(pl.callid, "RTP_START_FAILED", 500);
            onCancel(pl.sip, pl.callid, 500);
        }
    }

    /**
     * Triggered when an outbound call (INVITE) was accepted.
     */

    public boolean callInviteSuccess(int xline, SDP sdp) {
        PhoneLine pl = lines[xline];
        try {
            pl.sdp = sdp;
            SDP.Stream astream = sdp.getFirstAudioStream();
            if ((!astream.hasCodec(RTP.CODEC_G729a) || !Settings.current.hasAudioCodec(RTP.CODEC_G729a))
                    && (!astream.hasCodec(RTP.CODEC_G711u) || !Settings.current.hasAudioCodec(RTP.CODEC_G711u))
                    && (!astream.hasCodec(RTP.CODEC_G711a) || !Settings.current.hasAudioCodec(RTP.CODEC_G711a))) {
                JFLog.log("err:callInviteSuccess() : No compatible audio codec returned");
                pl.sip.bye(pl.callid);
                onCancel(pl.sip, pl.callid, 415);
                return false;
            }
            if (!pl.audioRTP.start()) {
                throw new Exception("RTP.start() failed");
            }
            if (pl.audioRTP.createChannel(astream) == null) {
                throw new Exception("RTP.createChannel() failed");
            }
            if (pl.sdp.getFirstAudioStream().isSecure()) {
                SRTPChannel channel = (SRTPChannel) pl.audioRTP.getDefaultChannel();
                if (pl.sdp.getFirstAudioStream().keyExchange == SDP.KeyExchange.SDP) {
                    SDP.Stream local = pl.localsdp.getFirstAudioStream();
                    SDP.Key localKey = local.getKey("AES_CM_128_HMAC_SHA1_80");
                    if (localKey == null) throw new Exception("Local SRTP keys not found");
                    channel.setLocalKeys(localKey.key, localKey.salt);
                    SDP.Stream remote = pl.sdp.getFirstAudioStream();
                    SDP.Key remoteKey = remote.getKey("AES_CM_128_HMAC_SHA1_80");
                    if (remoteKey == null) throw new Exception("Remote SRTP keys not found");
                    channel.setRemoteKeys(remoteKey.key, remoteKey.salt);
                } else {
                    SDP.Stream local = pl.localsdp.getFirstAudioStream();
                    channel.setDTLS(false, local.sdp.iceufrag, local.sdp.icepwd);
                }
            }
            if (!pl.audioRTP.getDefaultChannel().start()) {
                throw new Exception("RTPChannel.start() failed");
            }
            if (Settings.current.aa) selectLine(xline);
        } catch (Exception e) {
            JFLog.log(e);
            pl.sip.bye(pl.callid);
            onCancel(pl.sip, pl.callid, 500);
            return false;
        }
        return true;
    }

    /**
     * Triggered when an outbound call (INVITE) was refused.
     */

    public void callInviteFail(int xline) {
        PhoneLine pl = lines[xline];
        pl.incall = false;
        pl.trying = false;
        if (pl.audioRTP != null) {
            pl.audioRTP.uninit();
        }
        pl.callid = "";
    }

    /**
     * Start or finish a call transfer.
     */

    public void transfer(String phone) {
        if (line == -1) return;
        PhoneLine pl = lines[line];
        pl.dial = phone;
        if (!pl.talking) return;
        pl.xfer = true;
        if (pl.dial.length() == 0) {
            //cancel xfer
            pl.status = "Connected";
        } else {
            pl.sip.refer(pl.callid, pl.dial);
            pl.status = "XFER Requested";
        }
        pl.xfer = false;
    }

    /**
     * Put a call into or out of hold.
     */

    public void hold() {
        if (line == -1) return;
        PhoneLine pl = lines[line];
        if (!pl.incall) return;
        pl.hld = !pl.hld;
        pl.sip.setHold(pl.callid, pl.hld);
        pl.sip.reinvite(pl.callid);
    }

    public boolean isHold() {
        if (line == -1) return false;
        return lines[line].hld;
    }

    /**
     * @deprecated не работает, если сервер остановить, то
     * будет всё-равно будет выдавать true
     * @return
     */
    @Deprecated
    public boolean isRegistered() {
        if (lines == null || line == -1) {
            return false;
        }
        return lines[line].sip.isRegistered();
    }

    public boolean isCall() {
        if (lines == null || line == -1) {
            return false;
        }
        return lines[line].incall;
    }

    /**
     * Toggles the conference state of a line.
     */

    public void conference() {
        if (line == -1) return;
        PhoneLine pl = lines[line];
        if (!pl.incall) return;
        pl.cnf = !pl.cnf;
    }

    /**
     * Creates a timer to send keep-alives on all SIP connections.  Keep alive are done every 30 seconds (many routers have a 60 second timeout).
     */

    public void keepAliveinit() {
        timerKeepAlive = new java.util.Timer();
        timerKeepAlive.scheduleAtFixedRate(new KeepAlive(), 0, 30 * 1000);
    }

    @Override
    public void rtpSamples(RTPChannel rtpChannel) {

    }

    @Override
    public void rtpDigit(RTPChannel rtpChannel, char c) {

    }

    @Override
    public void rtpPacket(RTPChannel rtpChannel, byte[] bytes, int i, int i2) {

    }

    @Override
    public void rtcpPacket(RTPChannel rtpChannel, byte[] bytes, int i, int i2) {

    }

    @Override
    public void rtpH263(RTPChannel rtpChannel, byte[] bytes, int i, int i2) {

    }

    @Override
    public void rtpH263_1998(RTPChannel rtpChannel, byte[] bytes, int i, int i2) {

    }

    @Override
    public void rtpH263_2000(RTPChannel rtpChannel, byte[] bytes, int i, int i2) {

    }

    @Override
    public void rtpH264(RTPChannel rtpChannel, byte[] bytes, int i, int i2) {

    }

    @Override
    public void rtpVP8(RTPChannel rtpChannel, byte[] bytes, int i, int i2) {

    }

    @Override
    public void rtpJPEG(RTPChannel rtpChannel, byte[] bytes, int i, int i2) {

    }

    @Override
    public void rtpInactive(RTPChannel rtpChannel) {

    }

    /**
     * TimerTask to perform keep-alives. on all SIP connections.
     */

    public class KeepAlive extends java.util.TimerTask {
        public void run() {
            for (int a = 0; a < 6; a++) {
                if (Settings.current.lines[a].same != -1) continue;
                PhoneLine pl = lines[a];
                if (pl.sip == null) continue;
                if (!pl.sip.isRegistered()) continue;
                pl.sip.keepalive();
                if (pl.talking) {
                    if (pl.audioRTP != null) pl.audioRTP.keepalive();
                }
            }
        }
    }

    /**
     * TimerTask that reregisters all SIP connection after they expire (every 3600 seconds).
     */

    public class ReRegisterExpires extends java.util.TimerTask {
        public void run() {
            for (int a = 0; a < 6; a++) {
                PhoneLine pl = lines[a];
                if (Settings.current.lines[a].same != -1) continue;
                if (pl.sip == null) continue;
                if (pl.noregister) continue;
                pl.sip.reregister();
            }
            registerRetries = 0;
            if (timerRegisterRetries != null) {
                timerRegisterRetries = new java.util.Timer();
                timerRegisterRetries.schedule(new ReRegisterRetries(), 1000);
            }
        }
    }

    /**
     * TimerTask that reregisters any SIP connections that have failed to register (checks every 1 second upto 5 attempts).
     */

    public class ReRegisterRetries extends java.util.TimerTask {
        public void run() {
            boolean again = false;
            if (registerRetries < 5) {
                for (int a = 0; a < 6; a++) {
                    if (Settings.current.lines[a].same != -1) continue;
                    PhoneLine pl = lines[a];
                    if (pl.sip == null) continue;
                    if (pl.unauth) continue;
                    if (pl.noregister) continue;
                    if (!pl.sip.isRegistered()) {
                        JFLog.log("warn:retry register on line:" + (a + 1));
                        pl.sip.reregister();
                        again = true;
                    }
                }
                registerRetries++;
                if (again) {
                    timerRegisterRetries = new java.util.Timer();
                    timerRegisterRetries.schedule(new ReRegisterRetries(), 1000);
                    return;
                }
            }
            for (int a = 0; a < 6; a++) {
                PhoneLine pl = lines[a];
                if (pl.sip == null) continue;
                if (pl.unauth) continue;
                if (pl.noregister) continue;
                if (!pl.sip.isRegistered()) {
                    pl.unauth = true;  //server not responding after 5 attempts to register
                    pl.status = "Server not responding";
                }
            }
            timerRegisterRetries = null;
        }
    }

    /**
     * Send a reINVITE when the callee returns multiple codecs to select only most prefered codec (if reinvite is enabled).
     */

    public boolean reinvite(PhoneLine pl) {
        SDP.Stream astream = pl.sdp.getFirstAudioStream();
        int acnt = 0;
        int vcnt = 0;
        if (SIP.hasCodec(astream.codecs, RTP.CODEC_G729a)) acnt++;
        if (SIP.hasCodec(astream.codecs, RTP.CODEC_G711u)) acnt++;
        if (SIP.hasCodec(astream.codecs, RTP.CODEC_G711a)) acnt++;

        if ((acnt > 1 || vcnt > 1) && (Settings.current.reinvite)) {
            //returned more than one audio codec, reinvite with only one codec
            //do NOT reINVITE from a 183 - server will respond with 491 (request pending) and abort the call
            pl.localsdp = getLocalSDPAccept(pl);
            pl.sip.reinvite(pl.callid, pl.localsdp);
            return true;
        }
        return false;
    }

//SIPClientInterface interface

    /**
     * SIPClientInterface.onRegister() : triggered when a SIPClient has confirmation of a registration with server.
     */

    public void onRegister(SIPClient sip, boolean status) {
        System.out.println("-----onRegister");
        if (status) {
            //success
            for (int a = 0; a < 6; a++) {
                PhoneLine pl = lines[a];
                if (pl.sip != sip) continue;
                if (pl.status.length() == 0) pl.status = "Ready (" + pl.user + ")";
                pl.auth = true;
                if (line == -1) {
                    selectLine(a);
                }
            }
            sip.subscribe(sip.getUser(), "message-summary", Settings.current.sipexpires);  //SUBSCRIBE to self for message-summary event (not needed with Asterisk but X-Lite does it)
            onRegister(sip);
            System.out.println("-----REGISTERED");
            eventListener.registered();
        } else {
            //failed
            for (int a = 0; a < 6; a++) {
                PhoneLine pl = lines[a];
                if (pl.sip == sip) {
                    pl.status = "Unauthorized";
                    pl.unauth = true;
                    if (line == a) selectLine(-1);
                }
            }
            System.out.println("-----UNREGISTERED");
            eventListener.unregistered();
        }
    }

    /**
     * SIPClientInterface.onTrying() : triggered when an INVITE returns status code 100 (TRYING).
     */

    public void onTrying(SIPClient sip, String callid) {
        System.out.println("-----onTrying");
        //is a line trying to do an invite
        for (int a = 0; a < 6; a++) {
            PhoneLine pl = lines[a];
            if ((pl.incall) && (!pl.trying)) {
                if (pl.callid.equals(callid)) {
                    pl.trying = true;
                    pl.status = "Trying";
                }
            }
        }
    }

    /**
     * SIPClientInterface.onRinging() : triggered when an INVITE returns status code 180 (RINGING).
     */

    public void onRinging(SIPClient sip, String callid) {
        System.out.println("-----onRinging");
        for (int a = 0; a < 6; a++) {
            PhoneLine pl = lines[a];
            if ((pl.incall) && (!pl.ringing)) {
                if (pl.callid.equals(callid)) {
                    pl.ringing = true;
                    pl.status = "Ringing";
                }
            }
        }
    }

    /**
     * SIPClientInterface.onSuccess() : triggered when an INVITE returns status code 200 (OK) or 183 (ringback).
     */

    public void onSuccess(SIPClient sip, String callid, SDP sdp, boolean complete) {
        System.out.println("-----onSuccess");
        JFLog.log("onSuccess : streams=" + sdp.streams.length);
        for (int s = 0; s < sdp.streams.length; s++) {
            JFLog.log("onSuccess : stream=" + sdp.streams[s].getType() + "," + sdp.streams[s].getMode() + "," + sdp.streams[s].content);
            SDP.Stream stream = sdp.streams[s];
            for (int c = 0; c < stream.codecs.length; c++) {
                JFLog.log("onSuccess : codecs[] = " + stream.codecs[c].name + ":" + stream.codecs[c].id);
            }
        }
        //is a line trying to do an invite or reinvite?
        for (int a = 0; a < 6; a++) {
            PhoneLine pl = lines[a];
            if (!pl.incall) continue;
            if (!pl.callid.equals(callid)) continue;
            if (!pl.talking) {
                pl.sdp = sdp;
                if (complete && reinvite(pl)) {return;}
                if (!callInviteSuccess(a, sdp)) {return;}
                if (complete) {
                    //200 call complete
                    pl.status = "Connected";
                    pl.talking = true;
                    pl.ringing = false;
                    pl.ringback = false;
                    System.out.println("-----SUCCESS");
                    eventListener.accepted();
                } else {
                    //183 call progress (ie: ringback tones)
                    pl.status = "Ringing";
                    pl.talking = true;  //NOTE:Must send silent data via RTP or firewall will BLOCK inbound anyways
                    pl.ringing = true;
                    pl.ringback = true;
                }
                return;
            } else {
                if (pl.ringback && complete) {
                    pl.status = "Connected";
                    pl.ringback = false;
                    pl.ringing = false;
                    pl.sdp = sdp;
                    if (reinvite(pl)) { return;}
                }
            }
            //update RTP data in case reINVITE changes them (or when making progress from 183 to 200)
            pl.sdp = sdp;
            change(a, sdp);
            return;
        }
        JFLog.log("err:onSuccess() for unknown call:" + callid);
    }

    /**
     * SIPClientInterface.onBye() : triggered when server terminates a call.
     */
    public void onBye(SIPClient sip, String callid) {
        System.out.println("-----onBye");
        for (int a = 0; a < 6; a++) {
            PhoneLine pl = lines[a];
            if (pl.incall) {
                if (pl.callid.equals(callid)) {
                    endLine(a);
                }
            }
        }
    }

    /**
     * SIPClientInterface.onInvite() : triggered when server send an INVITE to jphonelite.
     */
    public int onInvite(SIPClient sip, String callid, String fromid, String fromnumber, SDP sdp, String record) {
        System.out.println("-----onInvite");
        //NOTE : onInvite() can not change codecs (use SIP.reaccept() to do that)
        for (int s = 0; s < sdp.streams.length; s++) {
            JFLog.log("onInvite : stream=" + sdp.streams[s].getType() + "," + sdp.streams[s].getMode() + "," + sdp.streams[s].content);
            SDP.Stream stream = sdp.streams[s];
            for (int c = 0; c < stream.codecs.length; c++) {
                JFLog.log("onInvite : codecs[] = " + stream.codecs[c].name + ":" + stream.codecs[c].id);
            }
        }
        for (int a = 0; a < 6; a++) {
            PhoneLine pl = lines[a];
            if (pl.sip == sip) {
                if (pl.callid.equals(callid)) {
                    if (pl.talking) {
                        //reINVITE
                        pl.sdp = sdp;
                        change(a, sdp);
                        pl.localsdp = getLocalSDPAccept(pl);
                        pl.sip.reaccept(callid, pl.localsdp);  //send 200 rely with new SDP
                        return -1;  //do not send a reply
                    }
                    System.out.println("-----INCOMING");
                    eventListener.incoming(fromnumber, record);
                    return 180;  //reply RINGING
                }
                if (pl.incall) continue;
                if (pl.incoming) continue;
                pl.dial = fromnumber;
                pl.callerid = fromid;
                if ((pl.callerid == null) || (pl.callerid.trim().length() == 0)) pl.callerid = "Unknown";
                //ru.Settings.addCallLog(pl.dial);
                updateRecentList();
                pl.status = fromid + " is calling";
                pl.incoming = true;
                pl.sdp = sdp;
                pl.callid = callid;
                pl.ringing = true;
                if (Settings.current.aa) {
                    selectLine(a);
                    call(1, "");  //this will send a reply
                    return -1;  //do NOT send a reply
                }
                System.out.println("-----INCOMING");
                eventListener.incoming(fromnumber, record);
                return 180;  //reply RINGING
            }
        }
        return 486;  //reply BUSY
    }

    /**
     * SIPClientInterface.onCancel() : triggered when server send a CANCEL request after an INVITE, or an error occured.
     */

    public void onCancel(SIPClient sip, String callid, int code) {
        System.out.println("-----onCancel");
        for (int a = 0; a < 6; a++) {
            PhoneLine pl = lines[a];
            if (pl.callid.equals(callid)) {
                endLine(a);
                pl.status = "Hungup (" + code + ")";
            }
        }
    }

    /**
     * SIPClientInterface.onRefer() : triggered when the server accepts a transfer for processing (SIP code 202).
     */

    public void onRefer(SIPClient sip, String callid) {
        System.out.println("-----onRefer");
        //NOTE:SIP code 202 doesn't really tell you the transfer was successful, it just tells you the transfer is in progress.
        //     see onNotify() event="refer" to determine if transfer was successful
        for (int a = 0; a < 6; a++) {
            PhoneLine pl = lines[a];
            if (pl.callid.equals(callid)) {
                pl.status = "XFER Accepted";
            }
        }
    }

    /**
     * SIPClientInterface.onNotify() : processes SIP:NOTIFY messages.
     */

    public void onNotify(SIPClient sip, String callid, String event, String content) {
        System.out.println("-----onNotify");
        String contentLines[] = content.split("\r\n");
        event = event.toLowerCase();
        if (event.equals("message-summary")) {
            String msgwait = SIP.getHeader("Messages-Waiting:", contentLines);
            if (msgwait != null) {
                for (int a = 0; a < 6; a++) {
                    PhoneLine pl = lines[a];
                    if (pl.sip == sip) {
                        pl.msgwaiting = msgwait.equalsIgnoreCase("yes");
                    }
                }
            }
            return;
        }
        if (event.equals("presence")) {
            JFLog.log("note:Presence:" + content);
            if (!content.startsWith("<?xml")) {
                JFLog.log("Not valid presence data (1)");
                return;
            }
            content = content.replaceAll("\r", "").replaceAll("\n", "");
            XML xml = new XML();
            ByteArrayInputStream bais = new ByteArrayInputStream(content.getBytes());
            if (!xml.read(bais)) {
                JFLog.log("Not valid presence data (2)");
                return;
            }
            XML.XMLTag contact = xml.getTag(new String[]{"presence", "tuple", "contact"});
            if (contact == null) {
                JFLog.log("Not valid presence data (3)");
                return;
            }
            String fields[] = SIP.split("Unknown<" + contact.getContent() + ">");
            if (fields == null) {
                JFLog.log("Not valid presence data (4)");
                return;
            }
            XML.XMLTag status = xml.getTag(new String[]{"presence", "tuple", "status", "basic"});
            if (status == null) {
                JFLog.log("Not valid presence data (5)");
                return;
            }
            return;
        }
        if (event.equals("refer")) {
            eventListener.transferred();
        }
        String parts[] = event.split(";");
        if (parts[0].equals("refer")) {
            int notifyLine = -1;
            for (int a = 0; a < 6; a++) {
                if (lines[a].callid.equals(callid)) {
                    notifyLine = a;
                    break;
                }
            }
            if (notifyLine == -1) {
                JFLog.log("Received NOTIFY:REFER that doesn't match any lines");
                return;
            }
            parts = content.split(" ");  //SIP/2.0 code desc
            int code = JF.atoi(parts[1]);
            switch (code) {
                case 100:  //trying (not used by Asterisk)
                    lines[notifyLine].status = "XFER Trying";
                    break;
                case 180:  //ringing
                case 183:  //ringing (not used by Asterisk)
                    lines[notifyLine].status = "XFER Ringing";
                    break;
                case 200:  //refer successful
                case 202:  //accepted (not used by Asterisk)
                    lines[notifyLine].status = "XFER Success";
                    break;
                case 404:  //refer failed
                default:
                    lines[notifyLine].status = "XFER Failed (" + code + ")";
                    break;
            }
            return;
        }
        JFLog.log("Warning : unknown NOTIFY type : " + event);
    }

    @Override
    public void onMessage(SIPClient sipClient, String callid, String message) {
        eventListener.newMessage(message);
    }

    private synchronized int getlocalport() {
        int port = sipnext++;
        if (sipnext > sipmax) sipnext = sipmin;
        return port;
    }

    public void setSIPPortRange(int min, int max) {
        sipmin = min;
        sipmax = max;
        sipnext = min;
    }

    /**
     * Changes SDP/RTP details.
     */

    public void change(int xline, SDP sdp) {
        try {
            PhoneLine pl = lines[xline];
            boolean used[] = new boolean[sdp.streams.length];
            for (int c = 0; c < pl.audioRTP.channels.size(); ) {
                RTPChannel channel = pl.audioRTP.channels.get(c);
                boolean ok = false;
                for (int s = 0; s < sdp.streams.length; s++) {
                    SDP.Stream stream = sdp.streams[s];
                    if (stream.content.equals(channel.stream.content)) {
                        channel.change(stream);
                        ok = true;
                        used[s] = true;
                        break;
                    }
                }
                if (!ok) {
                    //RTPChannel no longer in use
                    pl.audioRTP.removeChannel(channel);
                } else {
                    c++;
                }
            }
        } catch (Exception e) {
            JFLog.log(e);
        }
    }

    private static byte crt[], privateKey[];
    private static String fingerprintSHA256;

    /**
     * Select a new line.
     */

    public void selectLine(int newline) {
        if (line != -1 && lines[line].xfer) {
            if (newline == -1) {  //???
                return;
            }
            if (!lines[newline].talking) {
                return;
            }
            lines[newline].sip.referLive(lines[newline].callid, lines[line].callid);
            return;
        }
        if (line != -1 && Settings.current.autohold && lines[line].talking && !lines[line].hld && !lines[line].cnf && newline != line) {
            hold();
        }
        //make sure line is valid
        //for (int a=0;a<6;a++) lineButtons[a].setSelected(false);
        if ((line != -1) && (lines[line].dtmf != 'x')) lines[line].dtmfend = true;
        if (newline == -1) {
            line = -1;
            return;
        }
        sound.selectLine(newline);
        if (Settings.current.autohold && lines[line].talking && lines[line].hld == true && newline != line) {
            hold();
        }
        line = newline;
    }

    public void callInviteUpdate() {
        updateRecentList();
        if ((Settings.current.usePublish)) lines[line].sip.publish("busy");
    }

    public void updateRecentList() {
        //To change body of implemented methods use File | ru.Settings | File Templates.
    }

    public void monitorSubscribe(SIPClient sip) {
        //subscribe for any that belong to sip
        String server = sip.getRemoteHost();
        for (int a = 0; a < monitorList.size(); a++) {
            String contact = monitorList.get(a);
            String fields[] = SIP.split(contact);
            if (fields[2].equalsIgnoreCase(server)) {
                sip.subscribe(fields[1], "presence", 3600);
            }
        }
    }

    public void onRegister(SIPClient sip) {
        if ((Settings.current.usePublish)) lines[line].sip.publish("open");
        monitorSubscribe(sip);
    }

    private void reset() {
        setPortRanges();
        setNAT();
        if (!sound.init(lines))
            JFLog.log("No compatible sound found");
        setScales();
        new Thread() {
            public void run() {
                reRegisterAll();
            }
        }.start();
    }

    private void setPortRanges() {
        if (Settings.current.sipRange && validSIPRange()) {
            this.setSIPPortRange(Settings.current.sipmin, Settings.current.sipmax);
        } else {
            this.setSIPPortRange(5061, 5199);
        }
        if (Settings.current.rtpRange && validRTPRange()) {
            RTP.setPortRange(Settings.current.rtpmin, Settings.current.rtpmax);
        } else {
            RTP.setPortRange(32768, 65534);
        }
    }

    private void setNAT() {
        SIPClient.NAT nat = null;
        switch (Settings.current.nat) {
            case 0:
                nat = SIPClient.NAT.None;
                break;
            case 2:
                nat = SIPClient.NAT.STUN;
                break;
            case 3:
                nat = SIPClient.NAT.TURN;
                break;
            case 4:
                nat = SIPClient.NAT.ICE;
                break;
        }
        SIPClient.setNAT(nat, Settings.current.natHost, Settings.current.natUser, Settings.current.natPass);
        SIPClient.useNATOnPrivateNetwork(Settings.current.natPrivate);
        SIPClient.setEnableRport(Settings.current.rport);
        SIPClient.setEnableReceived(Settings.current.received);
        if (Settings.current.nat >= 3) {
            RTP.enableTURN(Settings.current.natHost, Settings.current.natUser, Settings.current.natPass);
        } else {
            RTP.disableTURN();
        }
    }

    private boolean validSIPRange() {
        if (Settings.current.sipmin <= 0) return false;
        if (Settings.current.sipmax <= 0) return false;
        if (Settings.current.sipmax < Settings.current.sipmin) return false;
        if (Settings.current.sipmax - Settings.current.sipmin < 32) return false;
        return true;
    }

    private boolean validRTPRange() {
        if (Settings.current.rtpmin <= 0) return false;
        if (Settings.current.rtpmax <= 0) return false;
        if (Settings.current.rtpmax < Settings.current.rtpmin) return false;
        if (Settings.current.rtpmax - Settings.current.rtpmin < 128) return false;
        return true;
    }

    public void setScales() {
        if (sound.isSWVolRec()) {
            sound.setVolRec(Settings.current.volRecSW);
        } else {
            sound.setVolRec(Settings.current.volRecHW);
        }
        if (sound.isSWVolPlay()) {

            sound.setVolPlay(Settings.current.volPlaySW);
        } else {
            sound.setVolPlay(Settings.current.volPlayHW);
        }
    }

}
