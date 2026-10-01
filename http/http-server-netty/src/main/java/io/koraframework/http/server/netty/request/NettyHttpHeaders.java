package io.koraframework.http.server.netty.request;

import io.koraframework.http.common.header.AbstractHttpHeaders;
import io.koraframework.http.common.header.HttpHeaders;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;

@NullMarked
public final class NettyHttpHeaders extends AbstractHttpHeaders implements HttpHeaders {

    private final io.netty.handler.codec.http.HttpHeaders nettyHeaders;
    @Nullable
    private volatile Set<String> names;
    @Nullable
    private volatile List<Map.Entry<String, List<String>>> entries;

    public NettyHttpHeaders(io.netty.handler.codec.http.HttpHeaders nettyHeaders) {
        this.nettyHeaders = nettyHeaders;
    }

    @Nullable
    @Override
    public String getFirst(String headerName) {
        return nettyHeaders.get(headerName);
    }

    @Override
    @Nullable
    public List<String> getAll(String headerName) {
        List<String> all = nettyHeaders.getAll(headerName);
        if (all.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableList(all);
    }

    @Override
    public boolean has(String headerName) {
        return nettyHeaders.contains(headerName);
    }

    @Override
    public int size() {
        return nettyHeaders.names().size();
    }

    @Override
    public boolean isEmpty() {
        return nettyHeaders.isEmpty();
    }

    @Override
    public Set<String> names() {
        var names = this.names;
        if (names != null) {
            return names;
        }
        names = new LinkedHashSet<>();
        for (CharSequence headerName : nettyHeaders.names()) {
            names.add(headerName.toString().toLowerCase(Locale.ROOT));
        }
        return this.names = Collections.unmodifiableSet(names);
    }

    @Override
    public Iterator<Map.Entry<String, List<String>>> iterator() {
        var entries = this.entries;
        if (entries != null) {
            return entries.iterator();
        }
        entries = new ArrayList<>(nettyHeaders.names().size());
        for (String name : nettyHeaders.names()) {
            entries.add(Map.entry(name.toLowerCase(Locale.ROOT), Collections.unmodifiableList(nettyHeaders.getAll(name))));
        }
        return (this.entries = Collections.unmodifiableList(entries)).iterator();
    }

    @Override
    public String toString() {
        return nettyHeaders.toString();
    }
}
