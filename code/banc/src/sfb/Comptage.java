package sfb;

import java.io.*;
import java.net.*;

/** Socket qui compte les octets applicatifs émis et reçus (au-dessus de TCP). */
public final class Comptage extends Socket {
    public long envoyes, recus;
    private InputStream in;
    private OutputStream out;

    @Override public synchronized InputStream getInputStream() throws IOException {
        if (in == null) {
            InputStream s = super.getInputStream();
            in = new FilterInputStream(s) {
                @Override public int read() throws IOException { int b = super.read(); if (b >= 0) recus++; return b; }
                @Override public int read(byte[] b, int o, int l) throws IOException {
                    int n = super.read(b, o, l); if (n > 0) recus += n; return n; }
            };
        }
        return in;
    }

    @Override public synchronized OutputStream getOutputStream() throws IOException {
        if (out == null) {
            OutputStream s = super.getOutputStream();
            out = new FilterOutputStream(s) {
                @Override public void write(int b) throws IOException { super.write(b); envoyes++; }
                @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); envoyes += l; }
                @Override public void flush() throws IOException { s.flush(); }
                private final OutputStream out = s;
            };
        }
        return out;
    }
}
