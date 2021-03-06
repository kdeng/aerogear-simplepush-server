/**
 * JBoss, Home of Professional Open Source
 * Copyright Red Hat, Inc., and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.aerogear.simplepush.server.netty;

import static io.netty.handler.codec.http.HttpHeaders.Values.WEBSOCKET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.util.CharsetUtil.UTF_8;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerInvoker;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;

import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.jboss.aerogear.simplepush.protocol.Ack;
import org.jboss.aerogear.io.netty.handler.codec.sockjs.SockJsConfig;
import org.jboss.aerogear.io.netty.handler.codec.sockjs.SockJsService;
import org.jboss.aerogear.io.netty.handler.codec.sockjs.SockJsServiceFactory;
import org.jboss.aerogear.io.netty.handler.codec.sockjs.handler.CorsInboundHandler;
import org.jboss.aerogear.io.netty.handler.codec.sockjs.handler.CorsOutboundHandler;
import org.jboss.aerogear.io.netty.handler.codec.sockjs.handler.SockJsHandler;
import org.jboss.aerogear.io.netty.handler.codec.sockjs.transport.Transports;
import org.jboss.aerogear.simplepush.protocol.HelloResponse;
import org.jboss.aerogear.simplepush.protocol.MessageType;
import org.jboss.aerogear.simplepush.protocol.PingMessage;
import org.jboss.aerogear.simplepush.protocol.RegisterResponse;
import org.jboss.aerogear.simplepush.protocol.UnregisterResponse;
import org.jboss.aerogear.simplepush.protocol.impl.AckMessageImpl;
import org.jboss.aerogear.simplepush.protocol.impl.HelloResponseImpl;
import org.jboss.aerogear.simplepush.protocol.impl.NotificationMessageImpl;
import org.jboss.aerogear.simplepush.protocol.impl.PingMessageImpl;
import org.jboss.aerogear.simplepush.protocol.impl.RegisterResponseImpl;
import org.jboss.aerogear.simplepush.protocol.impl.UnregisterResponseImpl;
import org.jboss.aerogear.simplepush.protocol.impl.AckImpl;
import org.jboss.aerogear.simplepush.protocol.impl.json.JsonUtil;
import org.jboss.aerogear.simplepush.server.DefaultSimplePushConfig;
import org.jboss.aerogear.simplepush.server.DefaultSimplePushServer;
import org.jboss.aerogear.simplepush.server.SimplePushServer;
import org.jboss.aerogear.simplepush.server.SimplePushServerConfig;
import org.jboss.aerogear.simplepush.server.datastore.ChannelNotFoundException;
import org.jboss.aerogear.simplepush.server.datastore.DataStore;
import org.jboss.aerogear.simplepush.server.datastore.InMemoryDataStore;
import org.jboss.aerogear.simplepush.util.CryptoUtil;
import org.jboss.aerogear.simplepush.util.UUIDUtil;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class SimplePushSockJSServiceTest {

    private SockJsServiceFactory factory;
    private String sessionUrl;

    @Before
    public void setup() {
        factory = defaultFactory();
        sessionUrl = randomSessionIdUrl(factory);
    }

    @Test
    public void xhrPollingOpenFrame() throws Exception {
        final FullHttpResponse openFrameResponse = sendXhrOpenFrameRequest(factory, sessionUrl);
        assertThat(openFrameResponse.getStatus(), is(HttpResponseStatus.OK));
        assertThat(openFrameResponse.content().toString(UTF_8), equalTo("o\n"));
    }

    @Test
    public void xhrPollingHelloWithChannelId() throws Exception {
        final String uaid = UUIDUtil.newUAID();
        final String channelId = UUID.randomUUID().toString();
        sendXhrOpenFrameRequest(factory, sessionUrl);

        final FullHttpResponse sendResponse = sendXhrHelloMessageRequest(factory, sessionUrl, uaid, channelId);
        assertThat(sendResponse.getStatus(), is(HttpResponseStatus.NO_CONTENT));
        final HelloResponseImpl handshakeResponse = pollXhrHelloMessageResponse(factory, sessionUrl);
        assertThat(handshakeResponse.getUAID(), equalTo(uaid));
    }

    @Test
    public void xhrPollingHelloWithInvalidUaid() throws Exception {
        final String uaid = "non-valie2233??";
        final String channelId = UUID.randomUUID().toString();
        sendXhrOpenFrameRequest(factory, sessionUrl);

        final FullHttpResponse sendResponse = sendXhrHelloMessageRequest(factory, sessionUrl, uaid, channelId);
        assertThat(sendResponse.getStatus(), is(HttpResponseStatus.NO_CONTENT));
        final HelloResponseImpl handshakeResponse = pollXhrHelloMessageResponse(factory, sessionUrl);
        assertThat(handshakeResponse.getMessageType(), is(MessageType.Type.HELLO));
        assertThat(handshakeResponse.getUAID(), not(equalTo(uaid)));
    }

    @Test
    public void xhrPollingRegister() throws Exception {
        final String channelId = UUID.randomUUID().toString();
        sendXhrOpenFrameRequest(factory, sessionUrl);
        sendXhrHelloMessageRequest(factory, sessionUrl, UUIDUtil.newUAID());
        pollXhrHelloMessageResponse(factory, sessionUrl);

        final FullHttpResponse registerChannelIdRequest = sendXhrRegisterChannelIdRequest(factory, sessionUrl, channelId);
        assertThat(registerChannelIdRequest.getStatus(), is(HttpResponseStatus.NO_CONTENT));

        final RegisterResponseImpl registerChannelIdResponse = pollXhrRegisterChannelIdResponse(factory, sessionUrl);
        assertThat(registerChannelIdResponse.getChannelId(), equalTo(channelId));
        assertThat(registerChannelIdResponse.getStatus().getCode(), equalTo(200));
        assertThat(registerChannelIdResponse.getPushEndpoint().startsWith("http://127.0.0.1:7777/update/"), is(true));
    }

    @Test
    public void xhrPollingUnregister() throws Exception {
        final String channelId = UUID.randomUUID().toString();
        sendXhrOpenFrameRequest(factory, sessionUrl);
        sendXhrHelloMessageRequest(factory, sessionUrl, UUIDUtil.newUAID());
        pollXhrHelloMessageResponse(factory, sessionUrl);
        sendXhrRegisterChannelIdRequest(factory, sessionUrl, channelId);
        pollXhrRegisterChannelIdResponse(factory, sessionUrl);

        final FullHttpResponse unregisterChannelIdRequest = unregisterChannelIdRequest(factory, sessionUrl, channelId);
        assertThat(unregisterChannelIdRequest.getStatus(), is(HttpResponseStatus.NO_CONTENT));

        final UnregisterResponseImpl unregisterChannelIdResponse = unregisterChannelIdResponse(factory, sessionUrl);
        assertThat(unregisterChannelIdResponse.getStatus().getCode(), is(200));
        assertThat(unregisterChannelIdResponse.getChannelId(), equalTo(channelId));
    }

    @Test
    public void xhrPollingPing() throws Exception {
        sendXhrOpenFrameRequest(factory, sessionUrl);
        sendXhrHelloMessageRequest(factory, sessionUrl, UUIDUtil.newUAID());
        pollXhrHelloMessageResponse(factory, sessionUrl);

        final FullHttpResponse registerChannelIdRequest = sendXhrPingRequest(factory, sessionUrl);
        assertThat(registerChannelIdRequest.getStatus(), is(HttpResponseStatus.NO_CONTENT));

        final PingMessageImpl pingResponse = pollXhrPingMessageResponse(factory, sessionUrl);
        assertThat(pingResponse.getPingMessage(), equalTo(PingMessage.PING_MESSAGE));
    }

    @Test
    public void websocketUpgradeRequest() throws Exception {
        final EmbeddedChannel channel = createChannel(factory);
        final HttpResponse response = websocketHttpUpgradeRequest(sessionUrl, channel);
        assertThat(response.getStatus(), is(HttpResponseStatus.SWITCHING_PROTOCOLS));
        assertThat(response.headers().get(HttpHeaders.Names.UPGRADE), equalTo("websocket"));
        assertThat(response.headers().get(HttpHeaders.Names.CONNECTION), equalTo("Upgrade"));
        assertThat(response.headers().get(Names.SEC_WEBSOCKET_ACCEPT), equalTo("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
        channel.close();
    }

    public static HttpResponse decodeHttpResponse(final EmbeddedChannel channel) {
        final EmbeddedChannel ch = new EmbeddedChannel(new HttpResponseDecoder());
        ch.writeInbound(channel.readOutbound());
        return ch.readInbound();
    }

    public static FullHttpResponse decodeFullHttpResponse(final EmbeddedChannel channel) {
        final EmbeddedChannel ch = new EmbeddedChannel(new HttpResponseDecoder());
        ch.writeInbound(channel.outboundMessages().toArray());
        final HttpResponse response = ch.readInbound();
        final HttpContent content = ch.readInbound();
        final DefaultFullHttpResponse fullResponse;
        if (content != null) {
            fullResponse = new DefaultFullHttpResponse(response.getProtocolVersion(), response.getStatus(), content.content());
        } else {
            fullResponse = new DefaultFullHttpResponse(response.getProtocolVersion(), response.getStatus());
        }
        fullResponse.headers().add(response.headers());
        return fullResponse;
    }

    @Test
    public void rawWebSocketUpgradeRequest() throws Exception {
        final SimplePushServerConfig simplePushConfig = DefaultSimplePushConfig.create().password("test").build();
        final SockJsConfig sockjsConf = SockJsConfig.withPrefix("/simplepush").webSocketProtocols("push-notification").build();
        final byte[] privateKey = CryptoUtil.secretKey(simplePushConfig.password(), "someSaltForTesting".getBytes());
        final SimplePushServer pushServer = new DefaultSimplePushServer(new InMemoryDataStore(), simplePushConfig, privateKey);
        final SimplePushServiceFactory factory = new SimplePushServiceFactory(sockjsConf, pushServer);
        final EmbeddedChannel channel = createChannel(factory);
        final FullHttpRequest request = websocketUpgradeRequest(factory.config().prefix() + Transports.Type.WEBSOCKET.path());
        request.headers().set(Names.SEC_WEBSOCKET_PROTOCOL, "push-notification");
        channel.writeInbound(request);
        final FullHttpResponse response = decodeFullHttpResponse(channel);
        assertThat(response.getStatus(), is(HttpResponseStatus.SWITCHING_PROTOCOLS));
        assertThat(response.headers().get(HttpHeaders.Names.UPGRADE), equalTo("websocket"));
        assertThat(response.headers().get(HttpHeaders.Names.CONNECTION), equalTo("Upgrade"));
        assertThat(response.headers().get(Names.SEC_WEBSOCKET_PROTOCOL), equalTo("push-notification"));
        assertThat(response.headers().get(Names.SEC_WEBSOCKET_ACCEPT), equalTo("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
        channel.close();
    }

    @Test
    public void websocketHello() {
        final EmbeddedChannel channel = createWebSocketChannel(factory);
        final String uaid = UUIDUtil.newUAID();
        sendWebSocketHttpUpgradeRequest(sessionUrl, channel);

        final HelloResponse response = sendWebSocketHelloFrame(uaid, channel);
        assertThat(response.getMessageType(), equalTo(MessageType.Type.HELLO));
        assertThat(response.getUAID(), equalTo(uaid));
        channel.close();
    }

    @Test
    public void websocketHelloWithInvalidUaid() {
        final String uaid = "non-valie2233??";
        final EmbeddedChannel channel = createWebSocketChannel(factory);
        sendWebSocketHttpUpgradeRequest(sessionUrl, channel);

        final HelloResponse response = sendWebSocketHelloFrame(uaid, channel);
        assertThat(response.getMessageType(), equalTo(MessageType.Type.HELLO));
        assertThat(response.getUAID(), not(equalTo(uaid)));
        channel.close();
    }

    @Test
    public void websocketRegister() {
        final EmbeddedChannel channel = createWebSocketChannel(factory);
        final String channelId = UUID.randomUUID().toString();
        sendWebSocketHttpUpgradeRequest(sessionUrl, channel);
        sendWebSocketHelloFrame(UUIDUtil.newUAID(), channel);

        final RegisterResponse registerResponse = sendWebSocketRegisterFrame(channelId, channel);
        assertThat(registerResponse.getStatus().getCode(), is(200));
        assertThat(registerResponse.getChannelId(), equalTo(channelId));
        channel.close();
    }

    @Test
    public void websocketRegisterDuplicateChannelId() {
        final EmbeddedChannel channel = createWebSocketChannel(factory);
        final String channelId = UUID.randomUUID().toString();
        sendWebSocketHttpUpgradeRequest(sessionUrl, channel);
        sendWebSocketHelloFrame(UUIDUtil.newUAID(), channel);

        assertThat(sendWebSocketRegisterFrame(channelId, channel).getStatus().getCode(), is(200));
        assertThat(sendWebSocketRegisterFrame(channelId, channel).getStatus().getCode(), is(409));
        channel.close();
    }

    @Test
    public void websocketUnregister() {
        final EmbeddedChannel channel = createWebSocketChannel(factory);
        final String channelId = UUID.randomUUID().toString();
        sendWebSocketHttpUpgradeRequest(sessionUrl, channel);
        sendWebSocketHelloFrame(UUIDUtil.newUAID(), channel);
        sendWebSocketRegisterFrame(channelId, channel);

        final UnregisterResponse registerResponse = websocketUnRegisterFrame(channelId, channel);
        assertThat(registerResponse.getStatus().getCode(), is(200));
        channel.close();
    }

    @Test
    public void websocketUnregisterNonRegistered() {
        final EmbeddedChannel channel = createWebSocketChannel(factory);
        sendWebSocketHttpUpgradeRequest(sessionUrl, channel);
        sendWebSocketHelloFrame(UUIDUtil.newUAID(), channel);

        final UnregisterResponse registerResponse = websocketUnRegisterFrame("notRegistered", channel);
        assertThat(registerResponse.getMessageType(), equalTo(MessageType.Type.UNREGISTER));
        assertThat(registerResponse.getChannelId(), equalTo("notRegistered"));
        assertThat(registerResponse.getStatus().getCode(), is(200));
        channel.close();
    }

    @Test
    public void websocketHandleAcknowledgement() throws Exception {
        final SimplePushServer simplePushServer = defaultPushServer();
        final SockJsServiceFactory serviceFactory = defaultFactory(simplePushServer);
        final EmbeddedChannel channel = createWebSocketChannel(serviceFactory);
        final String uaid = UUIDUtil.newUAID();
        final String channelId = UUID.randomUUID().toString();
        sendWebSocketHttpUpgradeRequest(sessionUrl, channel);
        sendWebSocketHelloFrame(uaid, channel);
        final RegisterResponse registerResponse = sendWebSocketRegisterFrame(channelId, channel);
        final String endpointToken = extractEndpointToken(registerResponse.getPushEndpoint());
        sendNotification(endpointToken, 1L, simplePushServer);

        final Set<Ack> unacked = sendAcknowledge(channel, ack(channelId, 1L));
        assertThat(unacked.isEmpty(), is(true));
        channel.close();
    }

    @Test
    public void websocketHandleAcknowledgements() throws Exception {
        final SimplePushServer simplePushServer = defaultPushServer();
        final SockJsServiceFactory serviceFactory = defaultFactory(simplePushServer);
        final EmbeddedChannel channel = createWebSocketChannel(serviceFactory);
        final String uaid = UUIDUtil.newUAID();
        final String channelId1 = UUID.randomUUID().toString();
        final String channelId2 = UUID.randomUUID().toString();
        sendWebSocketHttpUpgradeRequest(sessionUrl, channel);
        sendWebSocketHelloFrame(uaid, channel);
        final RegisterResponse registerResponse1 = sendWebSocketRegisterFrame(channelId1, channel);
        final String endpointToken1 = extractEndpointToken(registerResponse1.getPushEndpoint());
        final RegisterResponse registerResponse2 = sendWebSocketRegisterFrame(channelId2, channel);
        final String endpointToken2 = extractEndpointToken(registerResponse2.getPushEndpoint());
        sendNotification(endpointToken1, 1L, simplePushServer);
        sendNotification(endpointToken2, 1L, simplePushServer);

        final Set<Ack> unacked = sendAcknowledge(channel, ack(channelId1, 1L), ack(channelId2, 1L));
        assertThat(unacked.isEmpty(), is(true));
        channel.close();
    }

    private String extractEndpointToken(final String pushEndpoint) {
        return pushEndpoint.substring(pushEndpoint.lastIndexOf('/') + 1);
    }

    @Test
    @Ignore("Need to figure out how to run a schedules job with the new EmbeddedChannel")
    // https://groups.google.com/forum/#!topic/netty/Q-_wat_9Odo
    public void websocketHandleOneUnacknowledgement() throws Exception {
        final SimplePushServer simplePushServer = defaultPushServer();
        final SockJsServiceFactory serviceFactory = defaultFactory(simplePushServer);
        final EmbeddedChannel channel = createWebSocketChannel(serviceFactory);
        final String uaid = UUIDUtil.newUAID();
        final String channelId1 = UUID.randomUUID().toString();
        final String channelId2 = UUID.randomUUID().toString();
        sendWebSocketHttpUpgradeRequest(sessionUrl, channel);
        sendWebSocketHelloFrame(uaid, channel);
        final RegisterResponse registerResponse1 = sendWebSocketRegisterFrame(channelId1, channel);
        final String endpointToken1 = extractEndpointToken(registerResponse1.getPushEndpoint());
        sendNotification(endpointToken1, 1L, simplePushServer);

        final RegisterResponse registerResponse2 = sendWebSocketRegisterFrame(channelId2, channel);
        final String endpointToken2 = extractEndpointToken(registerResponse2.getPushEndpoint());
        sendNotification(endpointToken2, 1L, simplePushServer);

        final Set<Ack> unacked = sendAcknowledge(channel, ack(channelId1, 1L));
        assertThat(unacked.size(), is(1));
        assertThat(unacked, hasItem(new AckImpl(channelId2, 1L)));
        channel.close();
    }

    @Test
    @Ignore("Need to figure out how to run a schedules job with the new EmbeddedChannel")
    // https://groups.google.com/forum/#!topic/netty/Q-_wat_9Odo
    public void websocketHandleUnacknowledgement() throws Exception {
        final SimplePushServer simplePushServer = defaultPushServer();
        final SockJsServiceFactory serviceFactory = defaultFactory(simplePushServer);
        final EmbeddedChannel channel = createWebSocketChannel(serviceFactory);
        final String uaid = UUIDUtil.newUAID();
        final String channelId1 = UUID.randomUUID().toString();
        final String channelId2 = UUID.randomUUID().toString();
        sendWebSocketHttpUpgradeRequest(sessionUrl, channel);
        sendWebSocketHelloFrame(uaid, channel);
        final RegisterResponse registerResponse1 = sendWebSocketRegisterFrame(channelId1, channel);
        final String endpointToken1 = extractEndpointToken(registerResponse1.getPushEndpoint());
        sendNotification(endpointToken1, 1L, simplePushServer);
        final RegisterResponse registerResponse2 = sendWebSocketRegisterFrame(channelId2, channel);
        final String endpointToken2 = extractEndpointToken(registerResponse2.getPushEndpoint());
        sendNotification(endpointToken2, 1L, simplePushServer);

        final Set<Ack> unacked = sendAcknowledge(channel);
        assertThat(unacked.size(), is(1));
        assertThat(unacked, hasItems(ack(channelId1, 1L), ack(channelId2, 1L)));
        channel.close();
    }

    @Test
    public void websocketPing() {
        final EmbeddedChannel channel = createWebSocketChannel(factory);
        sendWebSocketHttpUpgradeRequest(sessionUrl, channel);
        sendWebSocketHelloFrame(UUIDUtil.newUAID(), channel);

        final PingMessage pingResponse = sendWebSocketPingFrame(channel);
        assertThat(pingResponse.getPingMessage(), equalTo(PingMessage.PING_MESSAGE));
        channel.close();
    }

    private SimplePushServer defaultPushServer() {
        final DataStore store = new InMemoryDataStore();
        final SimplePushServerConfig config = DefaultSimplePushConfig.create().password("test").build();
        final byte[] privateKey = DefaultSimplePushServer.generateAndStorePrivateKey(store, config);
        return new DefaultSimplePushServer(store, config, privateKey);
    }

    private void sendNotification(final String endpointToken, final long version,
            final SimplePushServer simplePushServer) throws ChannelNotFoundException {
        simplePushServer.handleNotification(endpointToken, "version=" + version);
    }

    private Ack ack(final String channelId, final Long version) {
        return new AckImpl(channelId, version);
    }

    private Set<Ack> sendAcknowledge(final EmbeddedChannel channel, final Ack... acks) {
        final Set<Ack> ups = new HashSet<Ack>(Arrays.asList(acks));
        final TextWebSocketFrame ackFrame = ackFrame(ups);
        channel.writeInbound(ackFrame);
        channel.runPendingTasks();

        final Object out = channel.readOutbound();
        if (out == null) {
            return Collections.emptySet();
        }

        final NotificationMessageImpl unacked = responseToType(out, NotificationMessageImpl.class);
        return unacked.getAcks();
    }

    private TextWebSocketFrame ackFrame(final Set<Ack> acks) {
        return new TextWebSocketFrame(JsonUtil.toJson(new AckMessageImpl(acks)));
    }

    private RegisterResponseImpl sendWebSocketRegisterFrame(final String channelId, final EmbeddedChannel ch) {
        ch.writeInbound(TestUtil.registerChannelIdWebSocketFrame(channelId));
        return responseToType(readOutboundDiscardEmpty(ch), RegisterResponseImpl.class);
    }

    private PingMessageImpl sendWebSocketPingFrame(final EmbeddedChannel ch) {
        ch.writeInbound(TestUtil.pingWebSocketFrame());
        return responseToType(ch.readOutbound(), PingMessageImpl.class);
    }

    private UnregisterResponse websocketUnRegisterFrame(final String channelId, final EmbeddedChannel ch) {
        ch.writeInbound(TestUtil.unregisterChannelIdWebSocketFrame(channelId));
        return responseToType(ch.readOutbound(), UnregisterResponseImpl.class);
    }

    private HttpResponse websocketHttpUpgradeRequest(final String sessionUrl, final EmbeddedChannel ch) throws Exception{
        ch.writeInbound(websocketUpgradeRequest(sessionUrl + Transports.Type.WEBSOCKET.path()));
        return decodeHttpResponse(ch);
    }

    private void sendWebSocketHttpUpgradeRequest(final String sessionUrl, final EmbeddedChannel ch) {
        ch.writeInbound(websocketUpgradeRequest(sessionUrl + Transports.Type.WEBSOCKET.path()));
        // Discarding the Http upgrade response
        ch.readOutbound();
        ch.readOutbound();
        // Discard open frame
        ch.readOutbound();
        ch.readOutbound();
        ch.pipeline().remove("wsencoder");
    }

    private HelloResponse sendWebSocketHelloFrame(final String uaid, final EmbeddedChannel ch) {
        ch.writeInbound(TestUtil.helloWebSocketFrame(uaid));
        return responseToType(ch.readOutbound(), HelloResponseImpl.class);
    }

    private Object readOutboundDiscardEmpty(final EmbeddedChannel ch) {
        final Object obj = ch.readOutbound();
        if (obj instanceof ByteBuf) {
            final ByteBuf buf = (ByteBuf) obj;
            if (buf.capacity() == 0) {
                ReferenceCountUtil.release(buf);
                return ch.readOutbound();
            }
        }
        return obj;
    }

    private <T> T responseToType(final Object response, Class<T> type) {
        if (response instanceof TextWebSocketFrame) {
            final TextWebSocketFrame frame = (TextWebSocketFrame) response;
            String content = frame.text();
            if (content.startsWith("a[")) {
                content = TestUtil.extractJsonFromSockJSMessage(content);
            }
            return JsonUtil.fromJson(content, type);
        }
        throw new IllegalArgumentException("Response is expected to be of type TextWebSocketFrame was: " + response);
    }

    private FullHttpResponse sendXhrOpenFrameRequest(final SockJsServiceFactory factory, final String sessionUrl) throws Exception {
        final EmbeddedChannel openChannel = createChannel(factory);
        openChannel.writeInbound(httpGetRequest(sessionUrl + Transports.Type.XHR.path()));
        final FullHttpResponse openFrameResponse = decodeFullHttpResponse(openChannel);
        openChannel.close();
        return openFrameResponse;
    }

    private FullHttpResponse sendXhrHelloMessageRequest(final SockJsServiceFactory factory, final String sessionUrl,
            final String uaid, final String... channelIds) throws Exception {
        return xhrSend(factory, sessionUrl, TestUtil.helloSockJSFrame(uaid, channelIds));
    }

    private HelloResponseImpl pollXhrHelloMessageResponse(final SockJsServiceFactory factory, final String sessionUrl) throws Exception {
        final FullHttpResponse pollResponse = xhrPoll(factory, sessionUrl);
        assertThat(pollResponse.getStatus(), is(HttpResponseStatus.OK));

        final String helloJson = TestUtil.extractJsonFromSockJSMessage(pollResponse.content().toString(UTF_8));
        return JsonUtil.fromJson(helloJson, HelloResponseImpl.class);
    }

    private FullHttpResponse sendXhrRegisterChannelIdRequest(final SockJsServiceFactory factory, final String sessionUrl,
            final String channelId) throws Exception {
        return xhrSend(factory, sessionUrl, TestUtil.registerChannelIdMessageSockJSFrame(channelId));
    }

    private RegisterResponseImpl pollXhrRegisterChannelIdResponse(final SockJsServiceFactory factory, final String sessionUrl) throws Exception {
        final FullHttpResponse pollResponse = xhrPoll(factory, sessionUrl);
        assertThat(pollResponse.getStatus(), is(HttpResponseStatus.OK));

        final String json = TestUtil.extractJsonFromSockJSMessage(pollResponse.content().toString(UTF_8));
        return JsonUtil.fromJson(json, RegisterResponseImpl.class);
    }

    private FullHttpResponse unregisterChannelIdRequest(final SockJsServiceFactory factory, final String sessionUrl,
            final String channelId) throws Exception {
        return xhrSend(factory, sessionUrl, TestUtil.unregisterChannelIdMessageSockJSFrame(channelId));
    }

    private UnregisterResponseImpl unregisterChannelIdResponse(final SockJsServiceFactory factory, final String sessionUrl) throws Exception {
        final FullHttpResponse pollResponse = xhrPoll(factory, sessionUrl);
        assertThat(pollResponse.getStatus(), is(HttpResponseStatus.OK));

        final String json = TestUtil.extractJsonFromSockJSMessage(pollResponse.content().toString(UTF_8));
        return JsonUtil.fromJson(json, UnregisterResponseImpl.class);
    }

    private FullHttpResponse sendXhrPingRequest(final SockJsServiceFactory factory, final String sessionUrl) throws Exception {
        return xhrSend(factory, sessionUrl, TestUtil.pingSockJSFrame());
    }

    private PingMessageImpl pollXhrPingMessageResponse(final SockJsServiceFactory factory, final String sessionUrl) throws Exception {
        final FullHttpResponse pollResponse = xhrPoll(factory, sessionUrl);
        assertThat(pollResponse.getStatus(), is(HttpResponseStatus.OK));

        final String helloJson = TestUtil.extractJsonFromSockJSMessage(pollResponse.content().toString(UTF_8));
        return JsonUtil.fromJson(helloJson, PingMessageImpl.class);
    }

    private FullHttpResponse xhrSend(final SockJsServiceFactory factory, final String sessionUrl, final String content) throws Exception {
        final EmbeddedChannel sendChannel = createChannel(factory);
        final FullHttpRequest sendRequest = httpPostRequest(sessionUrl + Transports.Type.XHR_SEND.path());
        sendRequest.content().writeBytes(Unpooled.copiedBuffer(content, UTF_8));
        sendChannel.writeInbound(sendRequest);
        final FullHttpResponse sendResponse = decodeFullHttpResponse(sendChannel);
        sendChannel.close();
        return sendResponse;

    }

    private FullHttpResponse xhrPoll(final SockJsServiceFactory factory, final String sessionUrl) throws Exception {
        final EmbeddedChannel pollChannel = createChannel(factory);
        pollChannel.writeInbound(httpGetRequest(sessionUrl + Transports.Type.XHR.path()));
        return decodeFullHttpResponse(pollChannel);
    }

    private FullHttpRequest httpGetRequest(final String path) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
    }

    private FullHttpRequest websocketUpgradeRequest(final String path) {
        final FullHttpRequest req = new DefaultFullHttpRequest(HTTP_1_1, HttpMethod.GET, path);
        req.headers().set(Names.HOST, "server.test.com");
        req.headers().set(Names.UPGRADE, WEBSOCKET.toString());
        req.headers().set(Names.CONNECTION, "Upgrade");
        req.headers().set(Names.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        req.headers().set(Names.SEC_WEBSOCKET_ORIGIN, "http://test.com");
        req.headers().set(Names.SEC_WEBSOCKET_VERSION, "13");
        req.headers().set(Names.CONTENT_LENGTH, "0");
        return req;
    }

    private FullHttpRequest httpPostRequest(final String path) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path);
    }

    private SockJsServiceFactory defaultFactory() {
        final SimplePushServerConfig simplePushConfig = DefaultSimplePushConfig.create().password("test").build();
        final SockJsConfig sockjsConf = SockJsConfig.withPrefix("/simplepush").build();
        final byte[] privateKey = CryptoUtil.secretKey(simplePushConfig.password(), "someSaltForTesting".getBytes());
        final SimplePushServer pushServer = new DefaultSimplePushServer(new InMemoryDataStore(), simplePushConfig, privateKey);
        return new SimplePushServiceFactory(sockjsConf, pushServer);
    }

    private SockJsServiceFactory defaultFactory(final SimplePushServer simplePushServer) {
        final SockJsConfig sockJSConfig = SockJsConfig.withPrefix("/simplepush").build();
        return new SockJsServiceFactory() {
            @Override
            public SockJsService create() {
                return new SimplePushSockJSService(config(), simplePushServer);
            }

            @Override
            public SockJsConfig config() {
                return sockJSConfig;
            }
        };
    }

    private String randomSessionIdUrl(final SockJsServiceFactory factory) {
        return factory.config().prefix() + "/111/" + UUID.randomUUID().toString();
    }

    private EmbeddedChannel createChannel(final SockJsServiceFactory factory) {
        final EmbeddedChannel ch = new TestEmbeddedChannel(
                new HttpRequestDecoder(),
                new HttpResponseEncoder(),
                new CorsInboundHandler(),
                new SockJsHandler(factory),
                new CorsOutboundHandler());
        ch.pipeline().remove("EmbeddedChannel$LastInboundHandler#0");
        return ch;
    }

    private EmbeddedChannel createWebSocketChannel(final SockJsServiceFactory factory) {
        final EmbeddedChannel ch = new TestEmbeddedChannel(
                new HttpRequestDecoder(),
                new HttpResponseEncoder(),
                new CorsInboundHandler(),
                new SockJsHandler(factory),
                new CorsOutboundHandler());
        ch.pipeline().remove("EmbeddedChannel$LastInboundHandler#0");
        return ch;
    }

    private static class TestEmbeddedChannel extends EmbeddedChannel {

        public TestEmbeddedChannel(final ChannelHandler... handlers) {
            super(handlers);
        }

        @Override
        public Unsafe unsafe() {
            final AbstractUnsafe delegate = super.newUnsafe();
            return new TestUnsafe(delegate, new StubEmbeddedEventLoop(super.eventLoop()));
        }

        private class TestUnsafe implements Unsafe {

            private final Unsafe delegate;
            private final ChannelHandlerInvoker invoker;

            public TestUnsafe(final Unsafe delegate, final ChannelHandlerInvoker invoker) {
                this.delegate = delegate;
                this.invoker = invoker;
            }

            @Override
            public ChannelHandlerInvoker invoker() {
                return invoker;
            }

            @Override
            public SocketAddress localAddress() {
                return delegate.localAddress();
            }

            @Override
            public SocketAddress remoteAddress() {
                return delegate.remoteAddress();
            }

            @Override
            public void register(ChannelPromise promise) {
                delegate.register(promise);
            }

            @Override
            public void bind(SocketAddress localAddress, ChannelPromise promise) {
                delegate.bind(localAddress, promise);
            }

            @Override
            public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                delegate.connect(remoteAddress, localAddress, promise);
            }

            @Override
            public void disconnect(ChannelPromise promise) {
                delegate.disconnect(promise);
            }

            @Override
            public void close(ChannelPromise promise) {
                delegate.close(promise);
            }

            @Override
            public void closeForcibly() {
                delegate.closeForcibly();
            }

            @Override
            public void beginRead() {
                delegate.beginRead();
            }

            @Override
            public void write(Object msg, ChannelPromise promise) {
                delegate.write(msg, promise);
            }

            @Override
            public void flush() {
                delegate.flush();
            }

            @Override
            public ChannelPromise voidPromise() {
                return delegate.voidPromise();
            }

            @Override
            public ChannelOutboundBuffer outboundBuffer() {
                return delegate.outboundBuffer();
            }
        }

    }

}
