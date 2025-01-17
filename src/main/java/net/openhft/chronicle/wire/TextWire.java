/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.*;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.util.ObjectUtils;
import net.openhft.chronicle.core.util.StringUtils;
import net.openhft.chronicle.core.values.IntValue;
import net.openhft.chronicle.core.values.LongArrayValues;
import net.openhft.chronicle.core.values.LongValue;
import net.openhft.chronicle.wire.util.BooleanConsumer;
import net.openhft.chronicle.wire.util.ByteConsumer;
import net.openhft.chronicle.wire.util.FloatConsumer;
import net.openhft.chronicle.wire.util.ShortConsumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xerial.snappy.Snappy;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.*;

import static net.openhft.chronicle.bytes.Bytes.empty;
import static net.openhft.chronicle.bytes.NativeBytes.nativeBytes;
import static net.openhft.chronicle.core.util.ReadResolvable.readResolve;

/**
 * YAML Based wire format
 */
public class TextWire implements Wire, InternalWireIn {

    public static final BytesStore TYPE = BytesStore.wrap("!type ");
    static final String SEQ_MAP = "!seqmap";
    static final String NULL = "!null \"\"";
    static final BitSet STARTS_QUOTE_CHARS = new BitSet();
    static final BitSet QUOTE_CHARS = new BitSet();
    static final Logger LOG = LoggerFactory.getLogger(TextWire.class);
    static final ThreadLocal<StopCharTester> ESCAPED_QUOTES = ThreadLocal.withInitial(() -> StopCharTesters.QUOTES.escaping());
    static final ThreadLocal<StopCharTester> ESCAPED_SINGLE_QUOTES = ThreadLocal.withInitial(() -> StopCharTesters.SINGLE_QUOTES.escaping());
    static final ThreadLocal<StopCharsTester> ESCAPED_END_OF_TEXT = ThreadLocal.withInitial(() -> TextStopCharsTesters.END_OF_TEXT.escaping());
    static final BytesStore COMMA_SPACE = BytesStore.wrap(", ");
    static final BytesStore COMMA_NEW_LINE = BytesStore.wrap(",\n");
    static final BytesStore NEW_LINE = BytesStore.wrap("\n");
    static final BytesStore SPACE = BytesStore.wrap(" ");
    static final BytesStore END_FIELD = NEW_LINE;
    static final char[] HEX = "0123456789ABCDEF".toCharArray();

    static {
        for (char ch : "0123456789+- \t\',#:{}[]|>".toCharArray())
            STARTS_QUOTE_CHARS.set(ch);
        for (char ch : "\',#:{}[]|>".toCharArray())
            QUOTE_CHARS.set(ch);
    }

    private final Bytes<?> bytes;
    private final TextValueOut valueOut = new TextValueOut();
    private final TextValueIn valueIn = new TextValueIn();
    private final boolean use8bit;
    private boolean ready;
    private long lineStart = 0;

    public TextWire(Bytes bytes, boolean use8bit) {
        this.bytes = bytes;
        this.use8bit = use8bit;
    }

    public TextWire(Bytes bytes) {
        this(bytes, false);
    }

    public static TextWire fromFile(String name) throws IOException {
        return new TextWire(Bytes.wrapForRead(IOTools.readFile(name)), true);
    }

    @NotNull
    public static TextWire from(@NotNull String text) {
        return new TextWire(Bytes.from(text));
    }

    public static String asText(@NotNull Wire wire) {
        long pos = wire.bytes().readPosition();
        TextWire tw = new TextWire(nativeBytes());
        wire.copyTo(tw);
        wire.bytes().readPosition(pos);

        return tw.toString();
    }

    public static <ACS extends Appendable & CharSequence> void unescape(@NotNull ACS sb) {
        int end = 0;
        int length = sb.length();
        for (int i = 0; i < length; i++) {
            char ch = sb.charAt(i);
            if (ch == '\\' && i < length - 1) {
                char ch3 = sb.charAt(++i);
                switch (ch3) {
                    case 'b':
                        ch = '\b';
                        break;
                    case 'r':
                        ch = '\r';
                        break;
                    case 'n':
                        ch = '\n';
                        break;
                    case 't':
                        ch = '\t';
                        break;
                    case 'x':
                        ch = (char)
                                (Character.getNumericValue(sb.charAt(++i)) * 16 +
                                        Character.getNumericValue(sb.charAt(++i)));
                        break;
                    case 'u':
                        ch = (char)
                                (Character.getNumericValue(sb.charAt(++i)) * 4096 +
                                        Character.getNumericValue(sb.charAt(++i)) * 256 +
                                        Character.getNumericValue(sb.charAt(++i)) * 16 +
                                        Character.getNumericValue(sb.charAt(++i)));
                        break;
                    default:
                        ch = ch3;
                }
            }
            AppendableUtil.setCharAt(sb, end++, ch);
        }
        if (length != sb.length())
            throw new IllegalStateException("Length changed from " + length + " to " + sb.length() + " for " + sb);
        AppendableUtil.setLength(sb, end);
    }

    public String toString() {
        return bytes.toString();
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void setReady(boolean ready) {
        this.ready = ready;
    }

    @Override
    public void copyTo(@NotNull WireOut wire) {
        wire.bytes().write(bytes, bytes().readPosition(), bytes().readLimit());
    }

    @NotNull
    @Override
    public ValueIn read() {
        readField(Wires.acquireStringBuilder());
        return valueIn;
    }

    @NotNull
    private StringBuilder readField(@NotNull StringBuilder sb) {
        consumeWhiteSpace();
        if (peekCode() == ',') {
            bytes.readSkip(1);
            consumeWhiteSpace();
        }
        try {
            int ch = peekCode();
            if (ch == '"') {
                bytes.readSkip(1);

                parseUntil(sb, getEscapingQuotes());

                consumeWhiteSpace();
                ch = readCode();
                if (ch != ':')
                    throw new UnsupportedOperationException("Expected a : at " + bytes.toDebugString());

            } else if (ch < 0) {
                sb.setLength(0);
                return sb;

            } else {
                parseUntil(sb, getEscapingEndOfText());
            }
            unescape(sb);
        } catch (BufferUnderflowException ignored) {
        }
        //      consumeWhiteSpace();
        return sb;
    }

    @NotNull
    private StopCharsTester getEscapingEndOfText() {
        StopCharsTester escaping = ESCAPED_END_OF_TEXT.get();
        // reset it.
        escaping.isStopChar(' ', ' ');
        return escaping;
    }

    private StopCharTester getEscapingQuotes() {
        StopCharTester sct = ESCAPED_QUOTES.get();
        // reset it.
        sct.isStopChar(' ');
        return sct;
    }

    private StopCharTester getEscapingSingleQuotes() {
        StopCharTester sct = ESCAPED_SINGLE_QUOTES.get();
        // reset it.
        sct.isStopChar(' ');
        return sct;
    }

    void consumeWhiteSpace() {
        for (; ; ) {
            int codePoint = peekCode();
            if (codePoint == '#') {
                //noinspection StatementWithEmptyBody
                while (readCode() >= ' ') ;
                this.lineStart = bytes.readPosition();
            } else if (Character.isWhitespace(codePoint) || codePoint == ',') {
                if (codePoint == '\n' || codePoint == '\r')
                    this.lineStart = bytes.readPosition() + 1;
                bytes.readSkip(1);
            } else {
                break;
            }
        }
    }

    void consumeDocumentStart() {
        if (bytes.readRemaining() > 4) {
            long pos = bytes.readPosition();
            if (bytes.readByte(pos) == '-' && bytes.readByte(pos + 1) == '-' && bytes.readByte(pos + 2) == '-')
                bytes.readSkip(3);
        }
    }

    int peekCode() {
        return bytes.peekUnsignedByte();
    }

    /**
     * returns true if the next string is {@code str}
     *
     * @param source string
     * @return true if the strings are the same
     */
    private boolean peekStringIgnoreCase(@NotNull final String source) {
        if (source.isEmpty())
            return true;

        if (bytes.readRemaining() < 1)
            return false;

        long pos = bytes.readPosition();

        try {
            for (int i = 0; i < source.length(); i++) {
                if (Character.toLowerCase(source.charAt(i)) != Character.toLowerCase(bytes.readByte()))
                    return false;
            }
        } finally {
            bytes.readPosition(pos);
        }

        return true;
    }

    private int readCode() {
        if (bytes.readRemaining() < 1)
            return -1;
        return bytes.readUnsignedByte();
    }

    @NotNull
    @Override
    public ValueIn read(@NotNull WireKey key) {
        long position = bytes.readPosition();
        StringBuilder sb = readField(Wires.acquireStringBuilder());
        if (sb.length() == 0 || StringUtils.isEqual(sb, key.name()))
            return valueIn;
        bytes.readPosition(position);
        throw new UnsupportedOperationException("Unordered fields not supported yet. key=" + key
                .name() + ", was=" + sb + ", data='" + sb + "'");
    }

    @NotNull
    @Override
    public ValueIn read(@NotNull StringBuilder name) {
        consumeWhiteSpace();
        readField(name);
        return valueIn;
    }

    @NotNull
    @Override
    public ValueIn getValueIn() {
        return valueIn;
    }

    @NotNull
    @Override
    public Wire readComment(@NotNull StringBuilder s) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        bytes.clear();
    }

    @NotNull
    @Override
    public Bytes<?> bytes() {
        return bytes;
    }

    @Override
    public boolean hasMore() {
        consumeWhiteSpace();

        return bytes.readRemaining() > 0;
    }

    @NotNull
    @Override
    public ValueOut write() {
        return valueOut.write();
    }

    @NotNull
    @Override
    public ValueOut write(@NotNull WireKey key) {
        return valueOut.write(key);
    }

    @NotNull
    @Override
    public ValueOut writeValue() {
        return valueOut;
    }

    @NotNull
    @Override
    public ValueOut getValueOut() {
        return valueOut;
    }

    @NotNull
    @Override
    public Wire writeComment(@NotNull CharSequence s) {
        valueOut.writeComment(s);
        return this;
    }

    @NotNull
    @Override
    public WireOut addPadding(int paddingToAdd) {
        for (int i = 0; i < paddingToAdd; i++)
            bytes.append((bytes.writePosition() & 63) == 0 ? '\n' : ' ');
        return this;
    }

    void escape(@NotNull CharSequence s) {
        Quotes quotes = needsQuotes(s);
        if (quotes == Quotes.NONE) {
            escape0(s, quotes);
            return;
        }
        bytes.append(quotes.q);
        escape0(s, quotes);
        bytes.append(quotes.q);
    }

    private void escape0(@NotNull CharSequence s, TextWire.Quotes quotes) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    if (ch == quotes.q) {
                        bytes.append('\\').append(ch);
                    } else {
                        bytes.append(ch);
                    }
                    break;
                case '\'':
                    if (ch == quotes.q) {
                        bytes.append('\\').append(ch);
                    } else {
                        bytes.append(ch);
                    }
                    break;
                case '\\':
                    bytes.append('\\').append(ch);
                    break;
                case '\b':
                    bytes.append("\\b");
                    break;
                case '\t':
                    bytes.append("\\t");
                    break;
                case '\r':
                    bytes.append("\\r");
                    break;
                case '\n':
                    bytes.append("\\n");
                    break;
                default:
                    if (ch > 127) {
                        bytes.append("\\u");
                        bytes.append(HEX[(ch >> 12) & 0xF]);
                        bytes.append(HEX[(ch >> 8) & 0xF]);
                        bytes.append(HEX[(ch >> 4) & 0xF]);
                        bytes.append(HEX[ch & 0xF]);
                    } else {
                        bytes.append(ch);
                    }
                    break;
            }
        }
    }

    Quotes needsQuotes(@NotNull CharSequence s) {
        Quotes quotes = Quotes.NONE;
        if (s.length() == 0)
            return Quotes.DOUBLE;

        if (STARTS_QUOTE_CHARS.get(s.charAt(0)))
            return Quotes.DOUBLE;
        for (int i = 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (QUOTE_CHARS.get(ch))
                return Quotes.DOUBLE;
            if (ch == '"')
                quotes = Quotes.SINGLE;
        }
        return quotes;
    }

    @NotNull
    @Override
    public LongValue newLongReference() {
        return new TextLongReference();
    }

    @NotNull
    @Override
    public IntValue newIntReference() {
        return new TextIntReference();
    }

    @NotNull
    @Override
    public LongArrayValues newLongArrayReference() {
        return new TextLongArrayReference();
    }

    public void parseWord(StringBuilder sb) {
        parseUntil(sb, StopCharTesters.SPACE_STOP);
    }

    public void parseUntil(StringBuilder sb, StopCharTester testers) {
        if (use8bit)
            bytes.parse8bit(sb, testers);
        else
            bytes.parseUTF(sb, testers);
    }

    public void parseUntil(StringBuilder sb, StopCharsTester testers) {
        sb.setLength(0);
        if (use8bit) {
            AppendableUtil.read8bitAndAppend(bytes, sb, testers);
        } else {
            try {
                AppendableUtil.readUTFAndAppend(bytes, sb, testers);
            } catch (IOException e) {
                throw new IORuntimeException(e);
            }
        }
    }

    public void append(CharSequence cs) {
        if (use8bit)
            bytes.append8bit(cs);
        else
            bytes.append(cs);
    }

    public void append(CharSequence cs, int offset) {
        if (use8bit)
            bytes.append8bit(cs, offset, cs.length());
        else
            bytes.append(cs, offset, cs.length());
    }

    public Object readObject() {
        consumeWhiteSpace();
        consumeDocumentStart();
        return readObject(0);
    }

    Object readObject(int indentation) {
        consumeWhiteSpace();
        int code = peekCode();
        int indentation2 = indentation();
        if (indentation2 < indentation)
            return NoObject.NO_OBJECT;
        switch (code) {
            case '-':
                if (bytes.readByte(bytes.readPosition() + 1) == '-')
                    return NoObject.NO_OBJECT;

                return readList(indentation2);
            case '[':
                return readList();
            case '{':
                return readMap();
            case '!':
                return readTypedObject();
            default:
                return readMap(indentation2);
        }
    }

    private int indentation() {
        return Maths.toInt32(bytes.readPosition() - lineStart);
    }

    private Object readTypedObject() {
        return valueIn.object(Object.class);
    }

    private List readList() {
        throw new UnsupportedOperationException();
    }

    List readList(int indentation) {
        List<Object> objects = new ArrayList<>();
        while (peekCode() == '-') {
            if (indentation() < indentation)
                break;
            if (bytes.readByte(bytes.readPosition() + 1) == '-')
                break;
            long ls = lineStart;
            bytes.readSkip(1);
            consumeWhiteSpace();
            if (lineStart == ls) {
                objects.add(valueIn.objectWithInferredType(Object.class));
            } else {
                Object e = readObject(indentation);
                if (e != NoObject.NO_OBJECT)
                    objects.add(e);
            }
            consumeWhiteSpace();
        }

        return objects;
    }

    Map readMap() {
        bytes.readSkip(1);
        Map map = new LinkedHashMap<>();
        StringBuilder sb = Wires.acquireAnotherStringBuilder(Wires.acquireStringBuilder());
        while (bytes.readRemaining() > 0) {
            consumeWhiteSpace();
            if (peekCode() == '}') {
                bytes.readSkip(1);
                break;
            }
            read(sb);
            String key = Wires.INTERNER.intern(sb);
            Object value = valueIn.objectWithInferredType(Object.class);
            map.put(key, value);
        }
        return map;
    }

    private Map readMap(int indentation) {
        Map map = new LinkedHashMap<>();
        StringBuilder sb = Wires.acquireAnotherStringBuilder(Wires.acquireStringBuilder());
        while (bytes.readRemaining() > 0) {
            consumeWhiteSpace();
            if (indentation() < indentation || bytes.readRemaining() == 0)
                break;
            read(sb);
            String key = Wires.INTERNER.intern(sb);
            if (key.equals("..."))
                break;
            Object value = valueIn.objectWithInferredType(Object.class);
            map.put(key, value);
        }
        return map;
    }

    public void writeObject(Object o) {
        if (o instanceof Iterable) {
            for (Object o2 : (Iterable) o) {
                writeObject(o2, 2);
            }
        } else if (o instanceof Map) {
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) o).entrySet()) {
                write(() -> entry.getKey().toString()).object(entry.getValue());
            }
        } else if (o instanceof WriteMarshallable) {
            valueOut.typedMarshallable((WriteMarshallable) o);
        } else {
            valueOut.object(o);
        }
    }

    private void writeObject(Object o, int indentation) {
        bytes.append('-');
        bytes.append(' ');
        indentation(indentation - 2);
        valueOut.object(o);
    }

    private void indentation(int indentation) {
        while (indentation-- > 0)
            bytes.append(' ');
    }

    enum Quotes {
        NONE(' '), SINGLE('\''), DOUBLE('"');
        final char q;

        Quotes(char q) {
            this.q = q;
        }
    }

    enum NoObject {NO_OBJECT;}

    class TextValueOut implements ValueOut {
        int indentation = 0;
        @NotNull
        BytesStore sep = Bytes.empty();
        boolean leaf = false;

        void prependSeparator() {
            append(sep);
            if (sep.endsWith('\n'))
                indent();
            sep = Bytes.empty();
        }

        @NotNull
        @Override
        public ValueOut leaf() {
            leaf = true;
            return this;
        }

        @NotNull
        @Override
        public WireOut wireOut() {
            return TextWire.this;
        }

        private void indent() {
            for (int i = 0; i < indentation; i++) {
                bytes.append(' ');
                bytes.append(' ');
            }
        }

        public void elementSeparator() {
            if (indentation == 0) {
                sep = Bytes.empty();
                bytes.append('\n');

            } else {
                sep = leaf ? COMMA_SPACE : COMMA_NEW_LINE;
            }
        }

        @NotNull
        @Override
        public WireOut bool(@Nullable Boolean flag) {
            prependSeparator();
            append(flag == null ? "!" + NULL : flag ? "true" : "false");
            elementSeparator();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut text(@Nullable CharSequence s) {
            prependSeparator();
            if (s == null) {
                append("!" + NULL);
            } else {
                escape(s);
            }
            elementSeparator();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int8(byte i8) {
            prependSeparator();
            bytes.append(i8);
            elementSeparator();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut bytes(@Nullable BytesStore fromBytes) {

            if (isText(fromBytes))
                return text(fromBytes);

            int length = Maths.toInt32(fromBytes.readRemaining());
            byte[] byteArray = new byte[length];
            fromBytes.copyTo(byteArray);
            return bytes(byteArray);
        }

        @NotNull
        @Override
        public WireOut rawBytes(@NotNull byte[] value) {
            prependSeparator();
            bytes.write(value);
            elementSeparator();
            return TextWire.this;
        }

        private boolean isText(@Nullable BytesStore fromBytes) {

            if (fromBytes == null)
                return true;
            for (long i = fromBytes.readPosition(); i < fromBytes.readLimit(); i++) {
                int ch = fromBytes.readUnsignedByte(i);
                if ((ch < ' ' && ch != '\t') || ch >= 127)
                    return false;
            }
            return true;
        }

        @NotNull
        @Override
        public ValueOut writeLength(long remaining) {
            throw new UnsupportedOperationException();
        }

        @NotNull
        @Override
        public WireOut bytes(byte[] byteArray) {
            prependSeparator();
            append("!!binary ");
            append(Base64.getEncoder().encodeToString(byteArray));
            append(END_FIELD);
            elementSeparator();

            return TextWire.this;
        }

        @Override
        public WireOut snappy(byte[] compressedBytes) {
            prependSeparator();
            append("!!snappy ");
            append(Base64.getEncoder().encodeToString(compressedBytes));
            append(END_FIELD);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut uint8checked(int u8) {
            prependSeparator();
            bytes.append(u8);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int16(short i16) {
            prependSeparator();
            bytes.append(i16);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut uint16checked(int u16) {
            prependSeparator();
            bytes.append(u16);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut utf8(int codepoint) {
            prependSeparator();
            StringBuilder sb = Wires.acquireStringBuilder();
            sb.appendCodePoint(codepoint);
            text(sb);
            sep = empty();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int32(int i32) {
            prependSeparator();
            bytes.append(i32);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut uint32checked(long u32) {
            prependSeparator();
            bytes.append(u32);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int64(long i64) {
            prependSeparator();
            bytes.append(i64);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int64array(long capacity) {
            TextLongArrayReference.write(bytes, capacity);
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int64array(long capacity, @NotNull LongArrayValues values) {
            long pos = bytes.writePosition();
            TextLongArrayReference.write(bytes, capacity);
            ((Byteable) values).bytesStore(bytes, pos, bytes.writePosition() - pos);
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut float32(float f) {
            prependSeparator();
            bytes.append(f);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut float64(double d) {
            prependSeparator();
            bytes.append(d);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut time(@NotNull LocalTime localTime) {
            prependSeparator();
            append(localTime.toString());
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut zonedDateTime(@NotNull ZonedDateTime zonedDateTime) {
            prependSeparator();
            append(zonedDateTime.toString());
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut date(@NotNull LocalDate localDate) {
            prependSeparator();
            append(localDate.toString());
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public ValueOut type(@NotNull CharSequence typeName) {
            prependSeparator();
            bytes.append('!');
            append(typeName);
            sep = SPACE;
            return this;
        }

        @NotNull
        @Override
        public WireOut typeLiteral(@NotNull BiConsumer<Class, Bytes> typeTranslator, Class type) {
            prependSeparator();
            append(TYPE);
            typeTranslator.accept(type, bytes);
            elementSeparator();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut typeLiteral(@NotNull CharSequence type) {
            prependSeparator();
            append(TYPE);
            text(type);
            elementSeparator();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut uuid(@NotNull UUID uuid) {
            prependSeparator();
            append(sep);
            append(uuid.toString());
            elementSeparator();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int32forBinding(int value) {
            prependSeparator();
            TextIntReference.write(bytes, value);
            elementSeparator();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int32forBinding(int value, IntValue intValue) {
            throw new UnsupportedOperationException("todo");
        }

        @NotNull
        @Override
        public WireOut int64forBinding(long value) {
            prependSeparator();
            TextLongReference.write(bytes, value);
            elementSeparator();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int64forBinding(long value, LongValue longValue) {
            throw new UnsupportedOperationException("todo");
        }

        @NotNull
        @Override
        public WireOut sequence(@NotNull Consumer<ValueOut> writer) {
            pushState();
            bytes.append('[');
            sep = NEW_LINE;
            long pos = bytes.readPosition();
            writer.accept(this);
            if (bytes.writePosition() > pos + 1)
                bytes.append('\n');

            popState();
            indent();
            bytes.append(']');
            sep = END_FIELD;
            return TextWire.this;
        }

        @Override
        public WireOut array(@NotNull Consumer<ValueOut> writer, Class arrayType) {
            if (arrayType == String[].class) append("!String[] ");
            else {
                bytes.append('!');
                append(arrayType.getName());
                bytes.append(' ');
            }
            ;
            return sequence(writer);
        }

        private void popState() {
            indentation--;
            leaf = false;
        }

        private void pushState() {
            indentation++;
        }

        @NotNull
        @Override
        public WireOut marshallable(@NotNull WriteMarshallable object) {
            if (!leaf)
                pushState();

            prependSeparator();
            bytes.append('{');
            sep = leaf ? SPACE : END_FIELD;

            object.writeMarshallable(TextWire.this);

            if (!leaf)
                popState();
            else
                leaf = false;
            if (sep.startsWith(','))
                append(sep, 1);
            else
                prependSeparator();
            bytes.append('}');

            if (indentation == 0) {
                sep = empty();
                append(NEW_LINE);

            } else {
                sep = COMMA_NEW_LINE;
            }
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut map(@NotNull final Map map) {
            type(SEQ_MAP);
            bytes.append(' ');
            bytes.append('[');
            pushState();
            sep = END_FIELD;
            map.forEach((k, v) -> {
                prependSeparator();
                append("{ key: ");
                leaf();
                object2(k);
                sep = COMMA_NEW_LINE;
                prependSeparator();
                append("  value: ");
                leaf();
                object2(v);
                bytes.append(' ');
                bytes.append('}');
                sep = COMMA_NEW_LINE;
            });
            popState();
            sep = END_FIELD;
            prependSeparator();
            bytes.append(']');
            sep = END_FIELD;
            return TextWire.this;
        }

        private void object2(Object v) {
            if (v instanceof CharSequence)
                text((CharSequence) v);
            else if (v instanceof WriteMarshallable)
                typedMarshallable((WriteMarshallable) v);
            else if (v == null)
                append("!" + NULL);
            else
                text(String.valueOf(v));
        }

        @NotNull
        @Override
        public WireOut typedMap(@NotNull Map<? extends WriteMarshallable, ? extends Marshallable> map) {
            type(SEQ_MAP);
            map.forEach((k, v) -> sequence(w -> w.marshallable(m -> m
                    .write(() -> "key").typedMarshallable(k)
                    .write(() -> "value").typedMarshallable(v))));
            return TextWire.this;
        }

        @NotNull
        public ValueOut write() {
            append(sep);
            bytes.append('"');
            bytes.append('"');
            bytes.append(':');
            bytes.append(' ');
            sep = empty();
            return this;
        }

        @NotNull
        public ValueOut write(@NotNull WireKey key) {
            CharSequence name = key.name();
            if (name == null) name = Integer.toString(key.code());
            prependSeparator();
            escape(name);
            bytes.append(':');
            bytes.append(' ');
            return this;
        }

        public void writeComment(@NotNull CharSequence s) {
            prependSeparator();
            append(sep);
            bytes.append('#');
            bytes.append(' ');
            append(s);
            bytes.append('\n');
            sep = empty();
        }
    }

    class TextValueIn implements ValueIn {
        @NotNull
        @Override
        public WireIn bool(@NotNull BooleanConsumer flag) {
            consumeWhiteSpace();

            StringBuilder sb = Wires.acquireStringBuilder();
            if (textTo(sb) == null) {
                flag.accept(null);
                return TextWire.this;
            }

            flag.accept(StringUtils.isEqual(sb, "true"));
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn text(@NotNull Consumer<String> s) {
            StringBuilder sb = Wires.acquireStringBuilder();
            Object acs = textTo(sb);
            s.accept(acs == null ? null : acs.toString());
            return TextWire.this;
        }

        @Override
        public String text() {
            return StringUtils.toString(textTo(Wires.acquireStringBuilder()));
        }

        @Nullable
        @Override
        public StringBuilder textTo(@NotNull StringBuilder sb) {
            return textTo0(sb);
        }

        @Nullable
        @Override
        public Bytes textTo(@NotNull Bytes bytes) {
            return textTo0(bytes);
        }

        @Nullable
        <ACS extends Appendable & CharSequence> ACS textTo0(@NotNull ACS a) {
            consumeWhiteSpace();
            int ch = peekCode();

            switch (ch) {
                case '{': {
                    final long len = readLength();
                    try {
                        a.append(Bytes.toString(bytes, bytes.readPosition(), len));
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                    bytes.readSkip(len);

                    // read the next comma
                    bytes.skipTo(StopCharTesters.COMMA_STOP);

                    return a;

                }
                case '"': {
                    bytes.readSkip(1);
                    if (use8bit)
                        bytes.parse8bit(a, getEscapingQuotes());
                    else
                        bytes.parseUTF(a, getEscapingQuotes());
                    unescape(a);
                    int code = peekCode();
                    if (code == '"')
                        readCode();
                    break;

                }
                case '\'': {
                    bytes.readSkip(1);
                    if (use8bit)
                        bytes.parse8bit(a, getEscapingSingleQuotes());
                    else
                        bytes.parseUTF(a, getEscapingSingleQuotes());
                    unescape(a);
                    int code = peekCode();
                    if (code == '\'')
                        readCode();
                    break;

                }
                case '!': {
                    bytes.readSkip(1);
                    ch = peekCode();
                    if (ch == '!') {
                        bytes.readSkip(1);
                        StringBuilder sb = Wires.acquireStringBuilder();
                        parseWord(sb);
                        if (StringUtils.isEqual(sb, "null")) {
                            textTo(sb);
                            return null;
                        } else if (StringUtils.isEqual(sb, "snappy")) {
                            textTo(sb);
                            try {
                                //todo needs to be made efficient
                                byte[] decodedBytes = Base64.getDecoder().decode(sb.toString().getBytes());
                                String csq = Snappy.uncompressString(decodedBytes);
                                return (ACS) Wires.acquireStringBuilder().append(csq);
                            } catch (IOException e) {
                                throw new AssertionError(e);
                            }
                        }
                    } else {
                        StringBuilder sb = Wires.acquireStringBuilder();
                        textTo(sb);
                        // ignore the type.
                    }
                    break;
                }
                default: {
                    if (bytes.readRemaining() > 0) {
                        if (a instanceof Bytes || use8bit)
                            bytes.parse8bit(a, getEscapingEndOfText());
                        else
                            bytes.parseUTF(a, getEscapingEndOfText());

                    } else {
                        AppendableUtil.setLength(a, 0);
                    }
                    // trim trailing spaces.
                    while (a.length() > 0)
                        if (Character.isWhitespace(a.charAt(a.length() - 1)))
                            AppendableUtil.setLength(a, a.length() - 1);
                        else
                            break;
                    break;
                }
            }

            int prev = peekBack();
            if (prev == ':' || prev == '#' || prev == '}')
                bytes.readSkip(-1);
            return a;
        }

        private int peekBack() {
            while (bytes.readPosition() >= bytes.start()) {
                int prev = bytes.readUnsignedByte(bytes.readPosition() - 1);
                if (prev != ' ') {
                    if (prev == '\n' || prev == '\r') {
                        TextWire.this.lineStart = bytes.readPosition();
                    }
                    return prev;
                }
                bytes.readSkip(-1);
            }
            return -1;
        }

        @NotNull
        @Override
        public WireIn int8(@NotNull ByteConsumer i) {
            consumeWhiteSpace();
            i.accept((byte) bytes.parseLong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn bytesMatch(@NotNull BytesStore compareBytes, BooleanConsumer consumer) {
            throw new UnsupportedOperationException("todo");
        }

        @NotNull
        @Override
        public WireIn bytes(@NotNull Bytes toBytes) {
            return bytes(wi -> toBytes.write(wi.bytes()));
        }

        @NotNull
        public WireIn bytes(@NotNull ReadMarshallable bytesConsumer) {
            consumeWhiteSpace();

            // TODO needs to be made much more efficient.
            StringBuilder sb = Wires.acquireStringBuilder();
            if (peekCode() == '!') {
                parseWord(sb);
                String str = Wires.INTERNER.intern(sb);
                if (str.equals("!!binary")) {
                    AppendableUtil.setLength(sb, 0);
                    parseWord(sb);
                    byte[] decode = Base64.getDecoder().decode(sb.toString());
                    bytesConsumer.readMarshallable(new TextWire(Bytes.wrapForRead(decode)));

                } else if (str.equals("!!null")) {
                    bytesConsumer.readMarshallable(null);
                    parseWord(sb);
                } else {
                    throw new IORuntimeException("Unsupported type=" + str);
                }
            } else {
                textTo(sb);
                bytesConsumer.readMarshallable(new TextWire(Bytes.wrapForRead(sb.toString().getBytes())));
            }
            return TextWire.this;
        }

        @Nullable
        public byte[] bytes() {
            consumeWhiteSpace();
            // TODO needs to be made much more efficient.
            StringBuilder sb = Wires.acquireStringBuilder();
            if (peekCode() == '!') {
                parseWord(sb);
                String str = Wires.INTERNER.intern(sb);
                if (str.equals("!!binary")) {
                    AppendableUtil.setLength(sb, 0);
                    parseWord(sb);
                    byte[] decode = Base64.getDecoder().decode(Wires.INTERNER.intern(sb));
                    return decode;

                } else if (str.equals("!!null")) {
                    parseWord(sb);
                    return null;
                } else if (str.equals("!" + SEQ_MAP)) {
                    sb.append(bytes.toString());
                    // todo fix this.
                    return Wires.INTERNER.intern(sb).getBytes();

                } else {
                    throw new IllegalStateException("unsupported type=" + str);
                }

            } else {
                textTo(sb);
                // todo fix this.
                return sb.toString().getBytes();
            }
        }

        @Nullable
        @Override
        public byte[] snappy() {
            consumeWhiteSpace();
            // TODO needs to be made much more efficient.
            StringBuilder sb = Wires.acquireStringBuilder();
            if (peekCode() == '!') {
                parseWord(sb);
                String str = Wires.INTERNER.intern(sb);
                if (str.equals("!!snappy")) {
                    AppendableUtil.setLength(sb, 0);
                    parseWord(sb);
                    byte[] decode = Base64.getDecoder().decode(Wires.INTERNER.intern(sb));
                    return decode;
                }
                throw new AssertionError("Incorrect format for snappy");
            } else {
                textTo(sb);
                // todo fix this.
                return sb.toString().getBytes();
            }
        }


        @NotNull
        @Override
        public WireIn wireIn() {
            return TextWire.this;
        }

        @Override
        public long readLength() {
            consumeWhiteSpace();
            long start = bytes.readPosition();
            try {
                consumeWhiteSpace();
                int code = readCode();
                switch (code) {
                    case '{': {
                        int count = 1;
                        for (; ; ) {
                            byte b = bytes.readByte();
                            if (b == '{')
                                count += 1;
                            else if (b == '}') {
                                count -= 1;
                                if (count == 0)
                                    return bytes.readPosition() - start;
                            } else if (b == 0) {
                                return bytes.readPosition() - start - 1;
                            }
                            // do nothing
                        }
                    }

                    case '-': {
                        for (; ; ) {
                            byte b = bytes.readByte();
                            if (b < ' ')
                                return bytes.readLimit() - start - 1;
                            // do nothing
                        }
                    }

                    default:
                        // TODO needs to be made much more efficient.
                        bytes();
                        return bytes.readPosition() - start;
                }
            } finally {
                bytes.readPosition(start);
            }
        }

        private long readLengthMarshallable() {
            long start = bytes.readPosition();
            try {
                consumeWhiteSpace();
                int code = readCode();
                switch (code) {
                    case '{': {
                        int count = 1;
                        for (; ; ) {
                            int b = bytes.readByte();
                            if (b == '{') {
                                count += 1;
                            } else if (b == '}') {
                                count -= 1;
                                if (count == 0)
                                    return bytes.readPosition() - start;
                            } else if (b == 0) {
                                return bytes.readPosition() - start - 1;
                            }
                            // do nothing
                        }
                    }

                    default:
                        // TODO needs to be made much more efficient.
                        bytes();
                        return bytes.readPosition() - start;
                }
            } finally {
                bytes.readPosition(start);
            }
        }

        @NotNull
        @Override
        public WireIn uint8(@NotNull ShortConsumer i) {
            consumeWhiteSpace();
            i.accept((short) bytes.parseLong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn int16(@NotNull ShortConsumer i) {
            consumeWhiteSpace();
            i.accept((short) bytes.parseLong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn uint16(@NotNull IntConsumer i) {
            consumeWhiteSpace();
            i.accept((int) bytes.parseLong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn int32(@NotNull IntConsumer i) {
            consumeWhiteSpace();
            i.accept((int) bytes.parseLong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn uint32(@NotNull LongConsumer i) {
            consumeWhiteSpace();
            i.accept(bytes.parseLong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn int64(@NotNull LongConsumer i) {
            consumeWhiteSpace();
            i.accept(bytes.parseLong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn float32(@NotNull FloatConsumer v) {
            consumeWhiteSpace();
            v.accept((float) bytes.parseDouble());
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn float64(@NotNull DoubleConsumer v) {
            consumeWhiteSpace();
            v.accept(bytes.parseDouble());
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn time(@NotNull Consumer<LocalTime> localTime) {
            consumeWhiteSpace();
            StringBuilder sb = Wires.acquireStringBuilder();
            textTo(sb);
            localTime.accept(LocalTime.parse(Wires.INTERNER.intern(sb)));
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn zonedDateTime(@NotNull Consumer<ZonedDateTime> zonedDateTime) {
            consumeWhiteSpace();
            StringBuilder sb = Wires.acquireStringBuilder();
            textTo(sb);
            zonedDateTime.accept(ZonedDateTime.parse(Wires.INTERNER.intern(sb)));
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn date(@NotNull Consumer<LocalDate> localDate) {
            consumeWhiteSpace();
            StringBuilder sb = Wires.acquireStringBuilder();
            textTo(sb);
            localDate.accept(LocalDate.parse(Wires.INTERNER.intern(sb)));
            return TextWire.this;
        }

        @Override
        public boolean hasNext() {
            return bytes.readRemaining() > 0;
        }

        @Override
        public boolean hasNextSequenceItem() {
            consumeWhiteSpace();
            int ch = peekCode();
            if (ch == ',') {
                bytes.readSkip(1);
                return true;
            }
            return ch > 0 && ch != ']';
        }

        @NotNull
        @Override
        public WireIn uuid(@NotNull Consumer<UUID> uuid) {
            consumeWhiteSpace();
            StringBuilder sb = Wires.acquireStringBuilder();
            textTo(sb);
            uuid.accept(UUID.fromString(Wires.INTERNER.intern(sb)));
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn int64array(@Nullable LongArrayValues values, @NotNull Consumer<LongArrayValues> setter) {
            consumeWhiteSpace();
            if (!(values instanceof TextLongArrayReference)) {
                setter.accept(values = new TextLongArrayReference());
            }
            Byteable b = (Byteable) values;
            long length = TextLongArrayReference.peakLength(bytes, bytes.readPosition());
            b.bytesStore(bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn int64(@Nullable LongValue value) {
            consumeWhiteSpace();
            Byteable b = (Byteable) value;
            long length = b.maxSize();
            b.bytesStore(bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            consumeWhiteSpace();
            if (peekCode() == ',')
                bytes.readSkip(1);
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn int64(LongValue value, @NotNull Consumer<LongValue> setter) {
            if (!(value instanceof TextLongReference)) {
                setter.accept(value = new TextLongReference());
            }
            return int64(value);
        }

        @NotNull
        @Override
        public WireIn int32(IntValue value, @NotNull Consumer<IntValue> setter) {
            if (!(value instanceof TextIntReference)) {
                setter.accept(value = new TextIntReference());
            }
            Byteable b = (Byteable) value;
            long length = b.maxSize();
            b.bytesStore(bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            consumeWhiteSpace();
            if (peekCode() == ',')
                bytes.readSkip(1);
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn sequence(@NotNull Consumer<ValueIn> reader) {
            consumeWhiteSpace();
            char code = (char) readCode();
            if (code != '[')
                throw new IORuntimeException("Unsupported type " + code + " (" + code + ")");

            // this code was added to support empty sets
            consumeWhiteSpace();
            code = (char) peekCode();
            if (code == ']')
                return TextWire.this;

            reader.accept(TextWire.this.valueIn);

            consumeWhiteSpace();
            code = (char) peekCode();
            if (code != ']')
                throw new IORuntimeException("Expected a ] but got " + code + " (" + code + ")");

            return TextWire.this;
        }

        @Override
        public <T> T applyToMarshallable(@NotNull Function<WireIn, T> marshallableReader) {
            consumeWhiteSpace();
            int code = peekCode();
            if (code != '{')
                throw new IORuntimeException("Unsupported type " + (char) code);

            final long len = readLengthMarshallable();

            final long limit = bytes.readLimit();
            final long position = bytes.readPosition();

            try {
                // ensure that you can read past the end of this marshable object
                final long newLimit = position - 1 + len;
                bytes.readLimit(newLimit);
                bytes.readSkip(1); // skip the {
                consumeWhiteSpace();
                return marshallableReader.apply(TextWire.this);
            } finally {
                bytes.readLimit(limit);

                consumeWhiteSpace();
                code = readCode();
                if (code != '}')
                    throw new IORuntimeException("Unterminated { while reading marshallable "
                            + "bytes=" + Bytes.toString(bytes)
                    );
            }
        }

        @NotNull
        @Override
        public ValueIn type(@NotNull StringBuilder sb) {
            consumeWhiteSpace();
            int code = peekCode();
            sb.setLength(0);
            if (code == -1) {
                sb.append("java.lang.Void");
            } else if (code == '!') {
                readCode();

                parseUntil(sb, TextStopCharTesters.END_OF_TYPE);
            }
            return this;
        }


        @NotNull
        String stringForCode(int code) {
            return code < 0 ? "Unexpected end of input" : "'" + (char) code + "'";
        }

        @NotNull
        @Override
        public WireIn typeLiteralAsText(@NotNull Consumer<CharSequence> classNameConsumer) {
            consumeWhiteSpace();
            int code = readCode();
            if (!peekStringIgnoreCase("type "))
                throw new UnsupportedOperationException(stringForCode(code));
            bytes.readSkip("type ".length());
            StringBuilder sb = Wires.acquireStringBuilder();
            parseUntil(sb, TextStopCharTesters.END_OF_TYPE);
            classNameConsumer.accept(sb);
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn marshallable(@NotNull ReadMarshallable object) {
            consumeWhiteSpace();
            int code = peekCode();
            if (code == '!')
                type(Wires.acquireStringBuilder());
            else if (code != '{')
                throw new IORuntimeException("Unsupported type " + stringForCode(code));

            final long len = readLengthMarshallable();

            final long limit = bytes.readLimit();
            final long position = bytes.readPosition();

            final long newLimit = position - 1 + len;
            try {
                // ensure that you can read past the end of this marshable object

                bytes.readLimit(newLimit);
                bytes.readSkip(1); // skip the {
                consumeWhiteSpace();
                object.readMarshallable(TextWire.this);
            } finally {
                bytes.readLimit(limit);
                bytes.readPosition(newLimit);
            }

            consumeWhiteSpace();
            code = readCode();
            if (code != '}')
                throw new IORuntimeException("Unterminated { while reading marshallable " +
                        object + ",code='" + (char) code + "', bytes=" + Bytes.toString(bytes)
                );
            return TextWire.this;
        }

        @Nullable
        public <T extends ReadMarshallable> T typedMarshallable() {
            try {
                consumeWhiteSpace();
                int code = peekCode();
                if (code < 0)
                    throw new IllegalStateException("Cannot read nothing as a ReadMarshallable " + bytes.toDebugString());
                StringBuilder sb = Wires.acquireStringBuilder();
                if (code != '!')
                    throw new ClassCastException("Cannot convert to ReadMarshallable. " + bytes.toDebugString());

                readCode();
                parseUntil(sb, TextStopCharTesters.END_OF_TYPE);

                if (StringUtils.isEqual(sb, "!null")) {
                    text();
                    return null;
                }

                if (StringUtils.isEqual(sb, "!binary")) {
                    bytesStore();
                    return null;
                }

                // its possible that the object that you are allocating may not have a
                // default constructor
                final Class clazz = ClassAliasPool.CLASS_ALIASES.forName(sb);

                if (!ReadMarshallable.class.isAssignableFrom(clazz))
                    throw new ClassCastException("Cannot convert " + sb + " to ReadMarshallable.");

                Class<ReadMarshallable> clazz1 = (Class<ReadMarshallable>) clazz;
                final ReadMarshallable m = ObjectUtils.newInstance(clazz1);

                marshallable(m);
                return readResolve(m);
            } catch (Exception e) {
                throw new IORuntimeException(e);
            }
        }

        @Nullable
        @Override
        public <K, V> Map<K, V> map(@NotNull final Class<K> kClazz,
                                    @NotNull final Class<V> vClass,
                                    @NotNull final Map<K, V> usingMap) {
            consumeWhiteSpace();
            usingMap.clear();

            StringBuilder sb = Wires.acquireStringBuilder();
            if (peekCode() == '!') {
                parseUntil(sb, StopCharTesters.SPACE_STOP);
                String str = Wires.INTERNER.intern(sb);

                if (("!!null").contentEquals(sb)) {
                    text();
                    return null;

                } else if (("!" + SEQ_MAP).contentEquals(sb)) {
                    consumeWhiteSpace();
                    int start = readCode();
                    if (start != '[')
                        throw new IORuntimeException("Unsupported start of sequence : " + (char) start);
                    do {
                        marshallable(r -> {
                            final K k = r.read(() -> "key")
                                    .object(kClazz);
                            final V v = r.read(() -> "value")
                                    .object(vClass);
                            usingMap.put(k, v);
                        });
                    } while (hasNextSequenceItem());
                    return usingMap;

                } else {
                    throw new IORuntimeException("Unsupported type :" + str);
                }
            }
            return usingMap;
        }

        @Override
        public <K extends ReadMarshallable, V extends ReadMarshallable> void typedMap(@NotNull Map<K, V> usingMap) {
            consumeWhiteSpace();
            usingMap.clear();

            StringBuilder sb = Wires.acquireStringBuilder();
            if (peekCode() == '!') {
                parseUntil(sb, StopCharTesters.SPACE_STOP);
                String str = Wires.INTERNER.intern(sb);
                if (SEQ_MAP.contentEquals(sb)) {
                    while (hasNext()) {
                        sequence(s -> s.marshallable(r -> {
                            try {
                                @SuppressWarnings("unchecked")
                                final K k = r.read(() -> "key").typedMarshallable();
                                @SuppressWarnings("unchecked")
                                final V v = r.read(() -> "value").typedMarshallable();
                                usingMap.put(k, v);
                            } catch (Exception e) {
                                LOG.error("", e);
                            }
                        }));
                    }
                } else {
                    throw new IORuntimeException("Unsupported type " + str);
                }
            }
        }

        @Override
        public boolean bool() {
            consumeWhiteSpace();
            StringBuilder sb = Wires.acquireStringBuilder();
            if (textTo(sb) == null)
                throw new NullPointerException("value is null");

            return StringUtils.isEqual(sb, "true");
        }

        public byte int8() {
            long l = int64();
            if (l > Byte.MAX_VALUE || l < Byte.MIN_VALUE)
                throw new IllegalStateException("value=" + l + ", is greater or less than Byte.MAX_VALUE/MIN_VALUE");
            return (byte) l;
        }

        public short int16() {
            long l = int64();
            if (l > Short.MAX_VALUE || l < Short.MIN_VALUE)
                throw new IllegalStateException("value=" + l + ", is greater or less than Short.MAX_VALUE/MIN_VALUE");
            return (short) l;
        }

        public int int32() {
            long l = int64();
            if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE)
                throw new IllegalStateException("value=" + l + ", is greater or less than Integer.MAX_VALUE/MIN_VALUE");
            return (int) l;
        }

        public int uint16() {
            long l = int64();
            if (l > Integer.MAX_VALUE || l < 0)
                throw new IllegalStateException("value=" + l + ", is greater or less than Integer" +
                        ".MAX_VALUE/ZERO");
            return (int) l;
        }

        @Override
        public long int64() {
            consumeWhiteSpace();
            return bytes.parseLong();
        }

        @Override
        public double float64() {
            consumeWhiteSpace();
            return bytes.parseDouble();
        }

        @Override
        public float float32() {

            double d = float64();
            if ((double) (((float) d)) != d)
                throw new IllegalStateException("value=" + d + " can not be represented as a float");

            return (float) d;
        }

        /**
         * @return true if !!null "", if {@code true} reads the !!null "" up to the next STOP, if
         * {@code false} no  data is read  ( data is only peaked if {@code false} )
         */
        public boolean isNull() {
            consumeWhiteSpace();

            if (peekStringIgnoreCase("!!null \"\"")) {
                bytes.readSkip("!!null \"\"".length());
                // discard the text after it.
                //  text(Wires.acquireStringBuilder());
                return true;
            }

            return false;
        }

        @Override
        @Nullable
        public <E> E object(@Nullable E using,
                            @NotNull Class<E> clazz) {
            return ObjectUtils.convertTo(clazz, object0(using, clazz));
        }

        @Nullable
        @Override
        public <E> WireIn object(@NotNull Class<E> clazz, @NotNull Consumer<E> e) {
            e.accept(ObjectUtils.convertTo(clazz, object0(null, clazz)));
            return TextWire.this;
        }

        @Nullable
        Object object0(@Nullable Object using, @NotNull Class clazz) {
            consumeWhiteSpace();

            if (isNull())
                return null;

            if (byte[].class.isAssignableFrom(clazz))
                return bytes();

            if (BytesStore.class.isAssignableFrom(clazz)) {
                Bytes<byte[]> bytes = Bytes.wrapForRead(bytes());
                return bytes;
            }

            if (ReadMarshallable.class.isAssignableFrom(clazz)) {
                final Object v;
                if (using == null)
                    v = ObjectUtils.newInstance(clazz);
                else
                    v = using;

                valueIn.marshallable((ReadMarshallable) v);
                return readResolve(v);

            } else if (StringBuilder.class.isAssignableFrom(clazz)) {
                StringBuilder builder = (using == null)
                        ? Wires.acquireStringBuilder()
                        : (StringBuilder) using;
                valueIn.textTo(builder);
                return using;

            } else if (CharSequence.class.isAssignableFrom(clazz)) {
                //noinspection unchecked
                return valueIn.text();

            } else if (Long.class.isAssignableFrom(clazz)) {
                //noinspection unchecked
                return valueIn.int64();

            } else if (Double.class.isAssignableFrom(clazz)) {
                //noinspection unchecked
                return valueIn.float64();
            } else if (Integer.class.isAssignableFrom(clazz)) {
                //noinspection unchecked
                return valueIn.int32();

            } else if (Float.class.isAssignableFrom(clazz)) {
                //noinspection unchecked
                return valueIn.float32();

            } else if (Short.class.isAssignableFrom(clazz)) {
                //noinspection unchecked
                return valueIn.int16();

            } else if (Character.class.isAssignableFrom(clazz)) {
                //noinspection unchecked
                final String text = valueIn.text();
                if (text == null || text.length() == 0)
                    return null;
                return text.charAt(0);

            } else if (Byte.class.isAssignableFrom(clazz)) {
                //noinspection unchecked
                return valueIn.int8();

            } else if (Map.class.isAssignableFrom(clazz)) {
                //noinspection unchecked
                final Map result = new HashMap();
                valueIn.map(result);
                return result;

            } else {
                return objectWithInferredType(clazz);
            }
        }

        Object objectWithInferredType(@NotNull Class clazz) {
            consumeWhiteSpace();
            int code = peekCode();
            switch (code) {
                case '!':
                    return typedObject();
                case '-':
                    if (bytes.readByte(bytes.readPosition() + 1) == ' ')
                        return readList(indentation());
                    return valueIn.readNumber();
                case '[':
                    return readSequence(clazz);
                case '{':
                    return readMap();
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                case '+':
                    return valueIn.readNumber();
            }
            if (Enum.class.isAssignableFrom(clazz)) {
                StringBuilder sb = Wires.acquireStringBuilder();
                parseUntil(sb, TextStopCharTesters.END_OF_TYPE);
                return Wires.INTERNER.intern(sb);
            }
            String text = valueIn.text();
            switch (text) {
                case "true":
                    return Boolean.TRUE;
                case "false":
                    return Boolean.FALSE;
                default:
                    return text;
            }
        }

        private Object readNumber() {
            String s = text();
            String ss = s;
            if (s.length() > 40)
                return s;

            if (s.contains("_"))
                ss = s.replace("_", "");
            try {
                return Long.decode(ss);
            } catch (NumberFormatException e) {
            }
            try {
                return Double.parseDouble(ss);
            } catch (NumberFormatException e) {
            }
            try {
                if (s.length() == 7 && s.charAt(1) == ':')
                    return LocalTime.parse("0" + s);
                if (s.length() == 8 && s.charAt(2) == ':')
                    return LocalTime.parse(s);
            } catch (DateTimeParseException e) {
            }
            try {
                if (s.length() == 10)
                    return LocalDate.parse(s);
            } catch (DateTimeParseException e) {
            }
            try {
                if (s.length() >= 22)
                    return ZonedDateTime.parse(s);
            } catch (DateTimeParseException e) {
            }
            return s;
        }

        @NotNull
        private Object readSequence(@NotNull Class clazz) {
            if (clazz == Object[].class || clazz == Object.class) {
                //todo should this use reflection so that all array types can be handled
                List<Object> list = new ArrayList<>();
                sequence(v -> {
                    while (v.hasNextSequenceItem()) {
                        list.add(v.object(Object.class));
                    }
                });
                return list.toArray();
            } else if (clazz == String[].class) {
                List<String> list = new ArrayList<>();
                sequence(v -> {
                    while (v.hasNextSequenceItem()) {
                        list.add(v.text());
                    }
                });
                return list.toArray(new String[0]);
            } else if (clazz == List.class) {
                List<String> list = new ArrayList<>();
                sequence(v -> {
                    while (v.hasNextSequenceItem()) {
                        list.add(v.text());
                    }
                });
                return list;
            } else if (clazz == Set.class) {
                Set<String> list = new HashSet<>();
                sequence(v -> {
                    while (v.hasNextSequenceItem()) {
                        list.add(v.text());
                    }
                });
                return list;
            } else {
                throw new UnsupportedOperationException("Arrays of type "
                        + clazz + " not supported.");
            }
        }

        private Object typedObject() {
            readCode();
            StringBuilder sb = Wires.acquireStringBuilder();
            parseUntil(sb, TextStopCharTesters.END_OF_TYPE);
            final Class clazz2 = ClassAliasPool.CLASS_ALIASES.forName(sb);
            return object(null, clazz2);
        }

        @Override
        public String toString() {
            return TextWire.this.toString();
        }
    }

}
