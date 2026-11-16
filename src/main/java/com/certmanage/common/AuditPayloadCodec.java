package com.certmanage.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.zip.*;

/**
 * @description 小载荷保持旧JSON，大载荷流式切换GZIP，避免持有完整明文副本
 * @author Freddy
 * @date 2026-11-16
 */
final class AuditPayloadCodec extends OutputStream {
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private OutputStream output = bytes;
  private boolean compressed;
  private int count;

  /**
   * @description 统计原JSON字节并在64KiB后切换压缩
   * @author Freddy
   * @date 2026-11-16
   * @param value 单字节
   * @throws IOException 压缩失败
   */
  @Override
  public void write(int value) throws IOException {
    write(new byte[] {(byte) value}, 0, 1);
  }

  /**
   * @description 在流式JSON编码过程中压缩，最多保留64KiB明文缓冲
   * @author Freddy
   * @date 2026-11-16
   * @param data 编码缓冲
   * @param offset 起始位置
   * @param length 字节数
   * @throws IOException 压缩失败
   */
  @Override
  public void write(byte[] data, int offset, int length) throws IOException {
    if (!compressed && count + length > 65536) {
      byte[] prefix = bytes.toByteArray();
      bytes.reset();
      output = new GZIPOutputStream(bytes);
      output.write(prefix);
      java.util.Arrays.fill(prefix, (byte) 0);
      compressed = true;
    }
    output.write(data, offset, length);
    count = Math.addExact(count, length);
  }

  /**
   * @description 完成压缩尾部
   * @author Freddy
   * @date 2026-11-16
   * @throws IOException 压缩失败
   */
  @Override
  public void close() throws IOException {
    if (compressed) ((GZIPOutputStream) output).finish();
  }

  /**
   * @description 返回可加密的JSON或GZIP以及原始大小
   * @author Freddy
   * @date 2026-11-16
   * @param json JSON编码器
   * @param payload 原始事件
   * @return 编码结果
   * @throws IOException 编码失败
   */
  static Encoded encode(ObjectMapper json, Object payload) throws IOException {
    var stream = new AuditPayloadCodec();
    json.writeValue(stream, payload);
    stream.close();
    return new Encoded(stream.bytes.toByteArray(), stream.count);
  }

  record Encoded(byte[] bytes, int size) {}

  /**
   * @description 根据GZIP魔数兼容旧JSON密文载荷
   * @author Freddy
   * @date 2026-11-16
   * @param bytes 解密后的编码载荷
   * @return 可流式解码的输入流
   * @throws IOException 压缩格式错误
   */
  static InputStream input(byte[] bytes) throws IOException {
    var stream = new ByteArrayInputStream(bytes);
    return bytes.length > 1 && (bytes[0] & 255) == 31 && (bytes[1] & 255) == 139
        ? new GZIPInputStream(stream)
        : stream;
  }
}
