/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_START_STREAM;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.openhab.binding.ipcamera.handler.IpCameraGroupHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;

/**
 * The {@link StreamServerGroupHandler} class is responsible for handling streams and sending any requested files to
 * Openhabs
 * features for a group of cameras instead of individual cameras.
 *
 * @author Matthew Skinner - Initial contribution
 */

@NonNullByDefault
public class StreamServerGroupHandler extends ChannelInboundHandlerAdapter {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private IpCameraGroupHandler ipCameraGroupHandler;
    byte @Nullable [] incomingJpeg = null;
    String whiteList = "";
    int recievedBytes = 0;
    int count = 0;
    boolean updateSnapshot = false;

    public StreamServerGroupHandler(IpCameraGroupHandler ipCameraGroupHandler) {
        this.ipCameraGroupHandler = ipCameraGroupHandler;
        whiteList = ipCameraGroupHandler.getWhiteList();
    }

    @Override
    public void handlerAdded(@Nullable ChannelHandlerContext ctx) {
    }

    private String resolveIndexToPath(String uri) {
        if (!uri.substring(1, 2).equals("i")) {
            return ipCameraGroupHandler.getOutputFolder(Integer.parseInt(uri.substring(1, 2)));
        }
        return "notFound";
        // example is /1ipcameraxx.ts
    }

    @Override
    public void channelRead(@Nullable ChannelHandlerContext ctx, @Nullable Object msg) throws Exception {
        if (msg == null || ctx == null) {
            return;
        }
        try {
            if (msg instanceof HttpRequest) {
                HttpRequest httpRequest = (HttpRequest) msg;
                // logger.debug("{}", httpRequest);
                logger.debug("Stream Server recieved request \t{}:{}", httpRequest.method(), httpRequest.uri());
                String requestIP = "("
                        + ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress() + ")";
                if (!whiteList.contains(requestIP) && !whiteList.equals("DISABLE")) {
                    logger.warn("The request made from {} was not in the whitelist and will be ignored.", requestIP);
                    return;
                } else if ("GET".equalsIgnoreCase(httpRequest.method().toString())) {
                    // Some browsers send a query string after the path when refreshing a picture.
                    QueryStringDecoder queryStringDecoder = new QueryStringDecoder(httpRequest.uri());
                    switch (queryStringDecoder.path()) {
                        case "/ipcamera.m3u8":
                            if (ipCameraGroupHandler.hlsTurnedOn) {
                                String debugMe = ipCameraGroupHandler.getPlayList();
                                logger.debug("playlist is:{}", debugMe);
                                sendString(ctx, debugMe, "application/x-mpegurl");
                            } else {
                                logger.warn(
                                        "HLS requires the groups startStream channel to be turned on first. Just starting it now.");
                                String channelPrefix = "ipcamera:" + ipCameraGroupHandler.getThing().getThingTypeUID()
                                        + ":" + ipCameraGroupHandler.getThing().getUID().getId() + ":";
                                ipCameraGroupHandler.handleCommand(new ChannelUID(channelPrefix + CHANNEL_START_STREAM),
                                        OnOffType.valueOf("ON"));
                            }
                            break;
                        case "/ipcamera.jpg":
                            sendSnapshotImage(ctx, "image/jpg");
                            break;
                        case "/snapshots.mjpeg":
                            logger.warn("snapshots.mjpeg is not yet implemented, use ipcamera.jpg or HLS.");
                            // ipCameraGroupHandler.setupSnapshotStreaming(true, ctx, false);
                            // handlingSnapshotStream = true;
                            break;
                        case "/ipcamera.mjpeg":
                            logger.warn("ipcamera.mjpeg is not yet implemented, use ipcamera.jpg or HLS.");
                            // ipCameraGroupHandler.setupMjpegStreaming(true, ctx);
                            // handlingMjpeg = true;
                            break;
                        case "/autofps.mjpeg":
                            logger.warn("autofps.mjpeg is not yet implemented, use ipcamera.jpg or HLS.");
                            // ipCameraGroupHandler.setupSnapshotStreaming(true, ctx, true);
                            // handlingSnapshotStream = true;
                            break;
                        default:
                            if (httpRequest.uri().contains(".ts")) {
                                // String path = resolveIndexToPath(httpRequest.uri());
                                sendFile(ctx, resolveIndexToPath(httpRequest.uri()) + httpRequest.uri().substring(2),
                                        "video/MP2T");
                            } else if (httpRequest.uri().contains(".jpg")) {
                                // Allow access to the preroll and postroll jpg files
                                sendFile(ctx, httpRequest.uri(), "image/jpg");
                            } else if (httpRequest.uri().contains(".m4s")) {
                                sendFile(ctx, httpRequest.uri(), "video/mp4");
                            } else if (httpRequest.uri().contains(".mp4")) {
                                sendFile(ctx, httpRequest.uri(), "video/mp4");
                            }
                    }
                }
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void sendSnapshotImage(ChannelHandlerContext ctx, String contentType) throws IOException {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        if (ipCameraGroupHandler.cameraIndex >= ipCameraGroupHandler.cameraOrder.size()) {
            logger.debug("WARN: Openhab may still be starting, or all cameras in the group are OFFLINE.");
            return;
        }
        ipCameraGroupHandler.cameraOrder.get(ipCameraGroupHandler.cameraIndex).lockCurrentSnapshot.lock();
        ByteBuf snapshotData = Unpooled
                .copiedBuffer(ipCameraGroupHandler.cameraOrder.get(ipCameraGroupHandler.cameraIndex).currentSnapshot);
        ipCameraGroupHandler.cameraOrder.get(ipCameraGroupHandler.cameraIndex).lockCurrentSnapshot.unlock();
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, snapshotData.readableBytes());
        response.headers().add("Access-Control-Allow-Origin", "*");
        response.headers().add("Access-Control-Expose-Headers", "*");
        ctx.channel().write(response);
        ctx.channel().write(snapshotData);
        ByteBuf footerBbuf = Unpooled.copiedBuffer("\r\n", 0, 2, StandardCharsets.UTF_8);
        ctx.channel().writeAndFlush(footerBbuf);
    }

    private void sendFile(ChannelHandlerContext ctx, String fileUri, String contentType) throws IOException {
        logger.debug("file is :{}", fileUri);
        File file = new File(fileUri);
        ChunkedFile chunkedFile = new ChunkedFile(file);
        ByteBuf footerBbuf = Unpooled.copiedBuffer("\r\n", 0, 2, StandardCharsets.UTF_8);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, chunkedFile.length());
        response.headers().add("Access-Control-Allow-Origin", "*");
        response.headers().add("Access-Control-Expose-Headers", "*");
        ctx.channel().write(response);
        ctx.channel().write(chunkedFile);
        ctx.channel().writeAndFlush(footerBbuf);
    }

    private void sendString(ChannelHandlerContext ctx, String contents, String contentType) throws IOException {
        ByteBuf contentsBbuf = Unpooled.copiedBuffer(contents, 0, contents.length(), StandardCharsets.UTF_8);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, contentsBbuf.readableBytes());
        response.headers().add("Access-Control-Allow-Origin", "*");
        response.headers().add("Access-Control-Expose-Headers", "*");
        ByteBuf footerBbuf = Unpooled.copiedBuffer("\r\n", 0, 2, StandardCharsets.UTF_8);
        ctx.channel().write(response);
        ctx.channel().write(contentsBbuf);
        ctx.channel().writeAndFlush(footerBbuf);
    }

    @Override
    public void channelReadComplete(@Nullable ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    public void exceptionCaught(@Nullable ChannelHandlerContext ctx, @Nullable Throwable cause) throws Exception {
        if (cause == null || ctx == null) {
            return;
        }
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
        if (evt == null || ctx == null) {
            return;
        }
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.WRITER_IDLE) {
                logger.debug("Stream server is going to close an idle channel.");
                ctx.close();
            }
        }
    }

    @Override
    public void handlerRemoved(@Nullable ChannelHandlerContext ctx) {
    }
}
