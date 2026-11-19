package uk.eba.ifitrecon;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.BinderChannelBuilder;
import io.grpc.binder.SecurityPolicy;
import io.grpc.stub.ClientCalls;

/** Talks raw gRPC-over-Binder to iFit's GlassOSPlatformService. Requests/responses are raw protobuf bytes. */
public class GrpcProbe {
    private final Context ctx;
    private ManagedChannel channel;

    public GrpcProbe(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    private synchronized ManagedChannel channel() {
        if (channel == null) {
            android.content.Intent bind = new android.content.Intent("com.ifit.glassos_service.GLASSOS_PLATFORM")
                    .setClassName("com.ifit.glassos_service", "com.ifit.glassos_service.GlassOSPlatformService");
            AndroidComponentAddress addr = AndroidComponentAddress.forBindIntent(bind);
            channel = BinderChannelBuilder.forAddress(addr, ctx)
                    .securityPolicy(new SecurityPolicy() {
                        @Override public Status checkAuthorization(int uid) { return Status.OK; }
                    })
                    .build();
        }
        return channel;
    }

    /** method like "com.ifit.glassos.VolumeService/GetVolume"; request = raw protobuf bytes (empty is valid for most). */
    public byte[] unary(String method, byte[] request) {
        MethodDescriptor<byte[], byte[]> md = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(method)
                .setRequestMarshaller(RAW).setResponseMarshaller(RAW)
                .build();
        return ClientCalls.blockingUnaryCall(channel(), md,
                CallOptions.DEFAULT.withDeadlineAfter(8, TimeUnit.SECONDS), request);
    }

    public synchronized void reset() {
        if (channel != null) channel.shutdownNow();
        channel = null;
    }

    static final MethodDescriptor.Marshaller<byte[]> RAW = new MethodDescriptor.Marshaller<byte[]>() {
        @Override public InputStream stream(byte[] value) { return new ByteArrayInputStream(value); }
        @Override public byte[] parse(InputStream stream) {
            try {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[4096]; int r;
                while ((r = stream.read(b)) > 0) o.write(b, 0, r);
                return o.toByteArray();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    };

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        if (s == null) return new byte[0];
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    /** Best-effort human dump of a protobuf message (field numbers + values). */
    static String decode(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        try {
            while (i < b.length) {
                long key = 0; int shift = 0; int c;
                do { c = b[i++] & 0xff; key |= (long) (c & 0x7f) << shift; shift += 7; } while ((c & 0x80) != 0);
                int field = (int) (key >>> 3), wt = (int) (key & 7);
                if (wt == 0) {
                    long v = 0; shift = 0;
                    do { c = b[i++] & 0xff; v |= (long) (c & 0x7f) << shift; shift += 7; } while ((c & 0x80) != 0);
                    sb.append(field).append("=").append(v).append(' ');
                } else if (wt == 1) {
                    long v = 0; for (int k = 0; k < 8; k++) v |= (long) (b[i + k] & 0xff) << (8 * k);
                    i += 8; sb.append(field).append("=").append(Double.longBitsToDouble(v)).append("d ");
                } else if (wt == 5) {
                    int v = 0; for (int k = 0; k < 4; k++) v |= (b[i + k] & 0xff) << (8 * k);
                    i += 4; sb.append(field).append("=").append(Float.intBitsToFloat(v)).append("f ");
                } else if (wt == 2) {
                    int len = 0; shift = 0;
                    do { c = b[i++] & 0xff; len |= (c & 0x7f) << shift; shift += 7; } while ((c & 0x80) != 0);
                    byte[] sub = new byte[len]; System.arraycopy(b, i, sub, 0, len); i += len;
                    String str = new String(sub, java.nio.charset.StandardCharsets.UTF_8);
                    boolean printable = str.matches("[\\x20-\\x7e]*");
                    sb.append(field).append("={").append(printable && len > 0 ? "\"" + str + "\"" : decode(sub)).append("} ");
                } else { sb.append("?wt").append(wt); break; }
            }
        } catch (Exception e) { sb.append("…"); }
        return sb.toString().trim();
    }
}
