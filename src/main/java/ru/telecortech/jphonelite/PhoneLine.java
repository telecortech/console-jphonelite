package ru.telecortech.jphonelite;
/**
 * Keeps track of each line.
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

import javaforce.voip.*;

public class PhoneLine {
    public boolean unauth, auth;
    public boolean noregister;
    public boolean incall;   //INVITE (outbound)
    public boolean trying;   //100 trying
    public boolean ringing;  //180 ringing
    public boolean ringback; //183 ringback tone
    public boolean talking;  //200 ok

    public boolean srtp;
    public SIP.Transport transport;
    public boolean dtls;

    public String user;

    public boolean incoming; //INVITE (inbound)

    public String dial = "", status = "";
    public String callid = "";  //Call-ID in SIP header (not callerid)
    public String to;  //remote number
    public String callerid;  //TEXT name of person calling

    public SIPClient sip;

    public RTP audioRTP;
    public SDP sdp, localsdp;
    public Coder coder;  //selected codec [en]coder

    public int clr = -1;

    public boolean xfer, hld, dnd, cnf;

    public short samples[] = new short[160];  //used in conference mode only

    //RFC 2833 - DTMF
    public char dtmf = 'x';
    public boolean dtmfend = false;

    public boolean msgwaiting = false;
}
