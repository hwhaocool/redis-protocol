package redis.server.netty;

import com.google.common.base.Charsets;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.netty4.Command;
import redis.netty4.ErrorReply;
import redis.netty4.InlineReply;
import redis.netty4.Reply;
import redis.util.BytesKey;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static redis.netty4.ErrorReply.NYI_REPLY;
import static redis.netty4.StatusReply.QUIT;

/**
 * Handle decoded commands
 */
@ChannelHandler.Sharable
public class RedisCommandHandler extends SimpleChannelInboundHandler<Command> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisCommandHandler.class);

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(10);

    private Map<BytesKey, Wrapper> methods = new HashMap<>();

    private int blockSeconds;

    interface Wrapper {
        Reply execute(Command command) throws RedisException;
    }

    public RedisCommandHandler(final RedisServer rs, final int blockSeconds) {

        this.blockSeconds = blockSeconds;

        Class<? extends RedisServer> aClass = rs.getClass();

        for (final Method method : aClass.getMethods()) {
            final Class<?>[] types = method.getParameterTypes();


            methods.put(new BytesKey(method.getName().getBytes()), new Wrapper() {

                @Override
                public Reply execute(Command command) throws RedisException {

                    LOGGER.info("current invoke method is {}", method.getName());

                    Object[] objects = new Object[types.length];
                    try {
                        command.toArguments(objects, types);

                        Reply reply = (Reply) method.invoke(rs, objects);

                        LOGGER.info("reply is {}", reply);

                        return reply;
                    } catch (IllegalAccessException e) {
                        throw new RedisException("Invalid server implementation");
                    } catch (InvocationTargetException e) {
                        Throwable te = e.getTargetException();
                        if (!(te instanceof RedisException)) {
                            te.printStackTrace();
                        }
                        return new ErrorReply("ERR " + te.getMessage());
                    } catch (Exception e) {
                        return new ErrorReply("ERR " + e.getMessage());
                    }
                }
            });
        }
    }

    private static final byte LOWER_DIFF = 'a' - 'A';

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Command msg) throws Exception {

        EXECUTOR_SERVICE.execute(() -> asyncRun(ctx, msg));
    }

    private void asyncRun(ChannelHandlerContext ctx, Command msg) {

        byte[] name = msg.getName();
        for (int i = 0; i < name.length; i++) {
            byte b = name[i];
            if (b >= 'A' && b <= 'Z') {
                name[i] = (byte) (b + LOWER_DIFF);
            }
        }

        LOGGER.info("command is {}", new String(name));

        String cName = new String(name, Charsets.US_ASCII);

        // 卡一下
        blockCommand(cName);

        Wrapper wrapper = methods.get(new BytesKey(name));
        Reply reply = null;
        if (wrapper == null) {
            LOGGER.info("unknown command '{}'", cName);

            reply = new ErrorReply("unknown command '" + cName + "'");
        } else {
            try {
                reply = wrapper.execute(msg);
            } catch (RedisException e) {
                LOGGER.info("wrapper execute,", e);
            }
        }
        if (reply == QUIT) {
            ctx.close();
        } else {
            if (msg.isInline()) {
                if (reply == null) {
                    reply = new InlineReply(null);
                } else {
                    reply = new InlineReply(reply.data());
                }
            }
            if (reply == null) {
                reply = NYI_REPLY;
            }
            ctx.writeAndFlush(reply);
        }
    }

    private void blockCommand(String command) {

        if (isSkip(command)) {
            return;
        }

        // 卡一下
        try {
            TimeUnit.SECONDS.sleep(blockSeconds);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 是否跳过这个命令，不去阻塞ta
     * @param command
     * @author YellowTail
     * @since 2020-06-29
     */
    private boolean isSkip(String command) {

        List<String> list = Arrays.asList("command", "ping");

        if (list.contains(command)) {
            return true;
        }

        return false;
    }
}
