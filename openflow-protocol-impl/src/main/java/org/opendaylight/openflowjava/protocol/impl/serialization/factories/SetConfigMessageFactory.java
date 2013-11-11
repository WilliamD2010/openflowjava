/* Copyright (C)2013 Pantheon Technologies, s.r.o. All rights reserved. */
package org.opendaylight.openflowjava.protocol.impl.serialization.factories;

import io.netty.buffer.ByteBuf;

import org.opendaylight.openflowjava.protocol.impl.serialization.OFSerializer;
import org.opendaylight.openflowjava.protocol.impl.util.ByteBufUtils;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.SetConfigInput;

/**
 * @author michal.polkorab
 *
 */
public class SetConfigMessageFactory implements OFSerializer<SetConfigInput> {

    /** Code type of SetConfig message */
    public static final byte MESSAGE_TYPE = 9;
    private static final int MESSAGE_LENGTH = 12;
    private static SetConfigMessageFactory instance;
    
    private SetConfigMessageFactory() {
        // do nothing, just singleton
    }
    
    /**
     * @return singleton factory
     */
    public static synchronized SetConfigMessageFactory getInstance() {
        if (instance == null) {
            instance = new SetConfigMessageFactory();
        }
        return instance;
    }
    
    @Override
    public void messageToBuffer(short version, ByteBuf out,
            SetConfigInput message) {
        ByteBufUtils.writeOFHeader(instance, message, out);
        out.writeShort(message.getFlags().getIntValue());
        out.writeShort(message.getMissSendLen());
    }

    @Override
    public int computeLength(SetConfigInput message) {
        return MESSAGE_LENGTH;
    }

    @Override
    public byte getMessageType() {
        return MESSAGE_TYPE;
    }

}
