/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.binding.ipcamera.internal;

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.*;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;

/**
 * The {@link StreamServerHandler} is responsible for handling streams and sending requested files
 *
 * @author Matthew Skinner - Initial contribution
 */

public class StreamServerHandler extends ChannelInboundHandlerAdapter {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private IpCameraHandler ipCameraHandler;
    private boolean handlingMjpeg = false; // used to remove ctx from group when handler is removed.
    private boolean handlingSnapshotStream = false; // used to remove ctx from group when handler is removed.
    byte[] incomingJpeg = null;
    int recievedBytes = 0;
    int count = 0;
    boolean updateSnapshot = false;

    public StreamServerHandler(IpCameraHandler ipCameraHandler) {
        this.ipCameraHandler = ipCameraHandler;
    }

    @Override
    public void handlerAdded(@Nullable ChannelHandlerContext ctx) {
    }

    @Override
    public void channelRead(@Nullable ChannelHandlerContext ctx, @Nullable Object msg) throws Exception {

        @Nullable
        HttpContent content = null;
        try {
            // logger.debug("{}", msg);
            if (msg instanceof HttpRequest) {
                HttpRequest httpRequest = (HttpRequest) msg;
                // logger.debug("{}", httpRequest);
                logger.debug("Stream Server recieved request \t{}:{}", httpRequest.method(), httpRequest.uri());
                String requestIP = "("
                        + ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress() + ")";
                if (!ipCameraHandler.config.get(CONFIG_IP_WHITELIST).toString().contains(requestIP)
                        && !ipCameraHandler.config.get(CONFIG_IP_WHITELIST).toString().contentEquals("DISABLE")) {
                    logger.warn("The request made from {} was not in the whitelist and will be ignored.", requestIP);
                    return;
                } else if ("GET".equalsIgnoreCase(httpRequest.method().toString())) {
                    switch (httpRequest.uri()) {
                        case "/ipcamera.m3u8":
                            ipCameraHandler.setupFfmpegFormat("HLS");
                            ipCameraHandler.ffmpegHLS.setKeepAlive(60);// setup must come first
                            sendFile(ctx, httpRequest.uri(), "application/x-mpegURL");
                            break;
                        case "/ipcamera.gif":
                            sendFile(ctx, httpRequest.uri(), "image/gif");
                            break;
                        case "/ipcamera.jpg":
                            if (!ipCameraHandler.updateImageEvents.contentEquals("1")) {
                                if (ipCameraHandler.snapshotUri != null) {
                                    ipCameraHandler.sendHttpGET(ipCameraHandler.snapshotUri);
                                }
                            }
                            sendSnapshotImage(ctx, "image/jpeg");
                            break;
                        case "/snapshots.mjpeg":
                            ipCameraHandler.setupSnapshotStreaming(true, ctx);
                            handlingSnapshotStream = true;
                            break;
                        case "/ipcamera.mjpeg":
                            ipCameraHandler.setupMjpegStreaming(true, ctx);
                            handlingMjpeg = true;
                            break;
                        case "/instar":
                            InstarHandler instar = new InstarHandler(ipCameraHandler);
                            instar.alarmTriggered(httpRequest.uri().toString());
                            break;
                        default:
                            if (httpRequest.uri().contains(".ts")) {
                                sendFile(ctx, httpRequest.uri(), "video/MP2T");
                            } else if (httpRequest.uri().contains(".jpg")) {
                                // Allow access to the preroll and postroll jpg files
                                sendFile(ctx, httpRequest.uri(), "image/jpeg");
                            }
                    }
                } else if ("POST".equalsIgnoreCase(httpRequest.method().toString())) {
                    switch (httpRequest.uri()) {
                        case "/ipcamera.jpg":
                            break;
                        case "/snapshot.jpg":
                            updateSnapshot = true;
                            break;
                    }
                }
            }
            if (msg instanceof HttpContent) {
                content = (HttpContent) msg;
                int index = 0;

                if (incomingJpeg == null) {
                    incomingJpeg = new byte[content.content().capacity()];
                } else {
                    byte[] temp = incomingJpeg;
                    incomingJpeg = new byte[recievedBytes + content.content().capacity()];

                    for (; index < temp.length; index++) {
                        incomingJpeg[index] = temp[index];
                    }
                }
                for (int i = 0; i < content.content().capacity(); i++) {
                    incomingJpeg[index++] = content.content().getByte(i);
                }
                recievedBytes = incomingJpeg.length;
                if (content instanceof LastHttpContent) {
                    if (updateSnapshot) {
                        ipCameraHandler.currentSnapshot = incomingJpeg;
                        ipCameraHandler.processSnapshot();
                    } else {
                        if (recievedBytes > 1000) {
                            ipCameraHandler.sendMjpegFrame(incomingJpeg, ipCameraHandler.mjpegChannelGroup);
                        }
                    }
                    incomingJpeg = null;
                    recievedBytes = 0;
                }
            }
        } finally

        {
            ReferenceCountUtil.release(msg);
        }
    }

    private void sendSnapshotImage(ChannelHandlerContext ctx, String contentType) throws IOException {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().add("Access-Control-Allow-Origin", "*");
        response.headers().add("Access-Control-Expose-Headers", "content-length");
        ByteBuf bbuf = Unpooled.copiedBuffer(ipCameraHandler.currentSnapshot);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, bbuf.readableBytes());
        ctx.channel().write(response);
        ctx.channel().writeAndFlush(bbuf);
    }

    private void sendFile(ChannelHandlerContext ctx, String fileUri, String contentType) throws IOException {
        File file = new File(ipCameraHandler.config.get(CONFIG_FFMPEG_OUTPUT).toString() + fileUri);
        ChunkedFile chunkedFile = new ChunkedFile(file);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, chunkedFile.length());
        response.headers().add("Access-Control-Allow-Origin", "*");
        response.headers().add("Access-Control-Expose-Headers", "content-length");
        ctx.channel().write(response);
        ctx.channel().writeAndFlush(chunkedFile);
    }

    @Override
    public void channelReadComplete(@Nullable ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    public void exceptionCaught(@Nullable ChannelHandlerContext ctx, @Nullable Throwable cause) throws Exception {
        if (cause.toString().contains("Connection reset by peer")) {
            logger.debug("Connection reset by peer.");
        } else if (cause.toString().contains("An established connection was aborted by the software")) {
            logger.debug("An established connection was aborted by the software");
        } else if (cause.toString().contains("An existing connection was forcibly closed by the remote host")) {
            logger.debug("An existing connection was forcibly closed by the remote host");
        } else if (cause.toString().contains("(No such file or directory)")) {
            logger.info(
                    "IpCameras file server could not find the requested file. This may happen if ffmpeg is still creating the file.");
        } else {
            logger.warn("Exception caught from stream server:{}", cause);
        }
        ctx.close();
    }

    @Override
    public void userEventTriggered(@Nullable ChannelHandlerContext ctx, @Nullable Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.WRITER_IDLE) {
                // logger.debug("Stream server is going to close an idle channel.");
                ctx.close();
            }
        }
    }

    @Override
    public void handlerRemoved(@Nullable ChannelHandlerContext ctx) {
        // logger.debug("Closing a StreamServerHandler.");
        if (handlingMjpeg) {
            ipCameraHandler.setupMjpegStreaming(false, ctx);
        } else if (handlingSnapshotStream) {
            handlingSnapshotStream = false;
            ipCameraHandler.setupSnapshotStreaming(false, ctx);
        }
    }
}
