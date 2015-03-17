package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.NativeBytes;
import net.openhft.chronicle.bytes.NoBytesStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static net.openhft.chronicle.bytes.NativeBytes.nativeBytes;
import static org.junit.Assert.*;

@RunWith(value = Parameterized.class)
public class BinaryWireTest {

    final int testId;
    final boolean fixed;
    final boolean numericField;
    final boolean fieldLess;

    public BinaryWireTest(int testId, boolean fixed, boolean numericField, boolean fieldLess) {
        this.testId = testId;
        this.fixed = fixed;
        this.numericField = numericField;
        this.fieldLess = fieldLess;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> combinations() {
        return Arrays.asList(
                new Object[]{0, false, false, false},
                new Object[]{1, true, false, false},
                new Object[]{2, false, true, false},
                new Object[]{3, true, true, false},
                new Object[]{4, false, false, true},
                new Object[]{5, true, false, true}
        );
    }

    Bytes bytes = nativeBytes();

    private BinaryWire createWire() {
        bytes.clear();
        return new BinaryWire(bytes, fixed, numericField, fieldLess);
    }


    enum BWKey implements WireKey {
        field1(1), field2(2), field3(3);
        private final int code;

        BWKey(int code) {
            this.code = code;
        }

        @Override
        public int code() {
            return code;
        }
    }

    @Test
    public void testWrite() {
        Wire wire = createWire();
        wire.write();
        wire.write();
        wire.write();
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 3, cap: 1TiB ] ÀÀÀ",
                "[pos: 0, lim: 3, cap: 1TiB ] ÀÀÀ",
                "[pos: 0, lim: 3, cap: 1TiB ] ÀÀÀ",
                "[pos: 0, lim: 3, cap: 1TiB ] ÀÀÀ",
                "[pos: 0, lim: 0, cap: 1TiB ] ",
                "[pos: 0, lim: 0, cap: 1TiB ] ");

        assertEquals(fieldLess ? "" : "\"\": \"\": \"\": ", TextWire.asText(wire));
    }

    private void checkWire(Wire wire, String... expected) {
        assertEquals("id: " + testId, expected[testId], wire.toString());
    }

    @Test
    public void testWrite1() {
        Wire wire = createWire();
        wire.write(BWKey.field1);
        wire.write(BWKey.field2);
        wire.write(BWKey.field3);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 21, cap: 1TiB ] Æfield1Æfield2Æfield3",
                "[pos: 0, lim: 21, cap: 1TiB ] Æfield1Æfield2Æfield3",
                "[pos: 0, lim: 6, cap: 1TiB ] ¹⒈¹⒉¹⒊",
                "[pos: 0, lim: 6, cap: 1TiB ] ¹⒈¹⒉¹⒊",
                "[pos: 0, lim: 0, cap: 1TiB ] ",
                "[pos: 0, lim: 0, cap: 1TiB ] ");
        checkAsText(wire,
                "field1: field2: field3: ",
                "1: 2: 3: ",
                "");
    }

    @Test
    public void testWrite2() {
        Wire wire = createWire();
        wire.write(() -> "Hello");
        wire.write(() -> "World");
        String name = "Long field name which is more than 32 characters, Bye";
        wire.write(() -> name);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 67, cap: 1TiB ] ÅHelloÅWorld·5" + name,
                "[pos: 0, lim: 67, cap: 1TiB ] ÅHelloÅWorld·5" + name,
                "[pos: 0, lim: 17, cap: 1TiB ] ¹²Ñ\u0098!¹òÖø'¹´Íýå\u0083٠",
                "[pos: 0, lim: 17, cap: 1TiB ] ¹²Ñ\u0098!¹òÖø'¹´Íýå\u0083٠",
                "[pos: 0, lim: 0, cap: 1TiB ] ",
                "[pos: 0, lim: 0, cap: 1TiB ] ");
        assertEquals(numericField ? "69609650: 83766130: -1019176629: " :
                fieldLess ? "" : "Hello: World: \"" + name + "\": ", TextWire.asText(wire));
    }

    @Test
    public void testRead() {
        Wire wire = createWire();
        wire.write();
        wire.write(BWKey.field1);
        wire.write(() -> "Test");
        wire.flip();
        checkAsText(wire, "\"\": field1: Test: ",
                "\"\": 1: 2603186: ",
                "");

        wire.read();
        wire.read();
        wire.read();
        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();
    }

    @Test
    public void testRead1() {
        Wire wire = createWire();
        wire.write();
        wire.write(BWKey.field1);
        wire.write(() -> "Test");
        wire.flip();
        checkAsText(wire, "\"\": field1: Test: ",
                "\"\": 1: 2603186: ",
                "");

        // ok as blank matches anything
        wire.read(BWKey.field1);
        wire.read(BWKey.field1);
        // not a match
        try {
            wire.read(BWKey.field1);
            if (!fieldLess) fail();
        } catch (UnsupportedOperationException expected) {
            wire.read(new StringBuilder());
        }
        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();
    }

    @Test
    public void testRead2() {
        Wire wire = createWire();
        wire.write();
        wire.write(BWKey.field1);
        String name1 = "Long field name which is more than 32 characters, Bye";
        wire.write(() -> name1);
        wire.flip();

        // ok as blank matches anything
        StringBuilder name = new StringBuilder();
        wire.read(name);
        assertEquals(0, name.length());

        name.setLength(0);
        wire.read(name);
        assertEquals(numericField ? "1" : fieldLess ? "" : BWKey.field1.name(), name.toString());

        name.setLength(0);
        wire.read(name);
        assertEquals(numericField ? "-1019176629" : fieldLess ? "" : name1, name.toString());

        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();
    }

    @Test
    public void int8() {
        Wire wire = createWire();
        wire.write().int8((byte) 1);
        wire.write(BWKey.field1).int8((byte) 2);
        wire.write(() -> "Test").int8((byte) 3);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 16, cap: 1TiB ] À⒈Æfield1⒉ÄTest⒊",
                "[pos: 0, lim: 19, cap: 1TiB ] À¢⒈Æfield1¢⒉ÄTest¢⒊",
                "[pos: 0, lim: 11, cap: 1TiB ] À⒈¹⒈⒉¹²ñ\u009E⒈⒊",
                "[pos: 0, lim: 14, cap: 1TiB ] À¢⒈¹⒈¢⒉¹²ñ\u009E⒈¢⒊",
                "[pos: 0, lim: 3, cap: 1TiB ] ⒈⒉⒊",
                "[pos: 0, lim: 6, cap: 1TiB ] ¢⒈¢⒉¢⒊");
        checkAsText123(wire);

        // ok as blank matches anything
        AtomicInteger i = new AtomicInteger();
        IntStream.rangeClosed(1, 3).forEach(e -> {
            wire.read().int8(i::set);
            assertEquals(e, i.get());
        });

        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();

    }

    @Test
    public void int16() {
        Wire wire = createWire();
        wire.write().int16((short) 1);
        wire.write(BWKey.field1).int16((short) 2);
        wire.write(() -> "Test").int16((short) 3);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 16, cap: 1TiB ] À⒈Æfield1⒉ÄTest⒊",
                "[pos: 0, lim: 22, cap: 1TiB ] À£⒈٠Æfield1£⒉٠ÄTest£⒊٠",
                "[pos: 0, lim: 11, cap: 1TiB ] À⒈¹⒈⒉¹²ñ\u009E⒈⒊",
                "[pos: 0, lim: 17, cap: 1TiB ] À£⒈٠¹⒈£⒉٠¹²ñ\u009E⒈£⒊٠",
                "[pos: 0, lim: 3, cap: 1TiB ] ⒈⒉⒊",
                "[pos: 0, lim: 9, cap: 1TiB ] £⒈٠£⒉٠£⒊٠");
        checkAsText123(wire);

        // ok as blank matches anything
        AtomicInteger i = new AtomicInteger();
        IntStream.rangeClosed(1, 3).forEach(e -> {
            wire.read().int16(i::set);
            assertEquals(e, i.get());
        });

        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();

    }

    @Test
    public void uint8() {
        Wire wire = createWire();
        wire.write().uint8(1);
        wire.write(BWKey.field1).uint8(2);
        wire.write(() -> "Test").uint8(3);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 16, cap: 1TiB ] À⒈Æfield1⒉ÄTest⒊",
                "[pos: 0, lim: 19, cap: 1TiB ] À¦⒈Æfield1¦⒉ÄTest¦⒊",
                "[pos: 0, lim: 11, cap: 1TiB ] À⒈¹⒈⒉¹²ñ\u009E⒈⒊",
                "[pos: 0, lim: 14, cap: 1TiB ] À¦⒈¹⒈¦⒉¹²ñ\u009E⒈¦⒊",
                "[pos: 0, lim: 3, cap: 1TiB ] ⒈⒉⒊",
                "[pos: 0, lim: 6, cap: 1TiB ] ¦⒈¦⒉¦⒊");
        checkAsText123(wire);

        // ok as blank matches anything
        AtomicInteger i = new AtomicInteger();
        IntStream.rangeClosed(1, 3).forEach(e -> {
            wire.read().uint8(i::set);
            assertEquals(e, i.get());
        });

        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();

    }

    @Test
    public void uint16() {
        Wire wire = createWire();
        wire.write().uint16(1);
        wire.write(BWKey.field1).uint16(2);
        wire.write(() -> "Test").uint16(3);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 16, cap: 1TiB ] À⒈Æfield1⒉ÄTest⒊",
                "[pos: 0, lim: 22, cap: 1TiB ] À§⒈٠Æfield1§⒉٠ÄTest§⒊٠",
                "[pos: 0, lim: 11, cap: 1TiB ] À⒈¹⒈⒉¹²ñ\u009E⒈⒊",
                "[pos: 0, lim: 17, cap: 1TiB ] À§⒈٠¹⒈§⒉٠¹²ñ\u009E⒈§⒊٠",
                "[pos: 0, lim: 3, cap: 1TiB ] ⒈⒉⒊",
                "[pos: 0, lim: 9, cap: 1TiB ] §⒈٠§⒉٠§⒊٠");
        checkAsText123(wire);

        // ok as blank matches anything
        AtomicInteger i = new AtomicInteger();
        IntStream.rangeClosed(1, 3).forEach(e -> {
            wire.read().uint16(i::set);
            assertEquals(e, i.get());
        });

        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();

    }

    private void checkAsText123(Wire wire) {
        checkAsText(wire, "\"\": 1\n" +
                        "field1: 2\n" +
                        "Test: 3\n",
                "\"\": 1\n" +
                        "1: 2\n" +
                        "2603186: 3\n",
                "1\n" +
                        "2\n" +
                        "3\n"
        );
    }

    private void checkAsText(Wire wire, String textFieldExcepted, String numberFieldExpected, String fieldLessExpected) {
        String text = TextWire.asText(wire);
        if (fieldLess)
            assertEquals(fieldLessExpected, text);
        else if (numericField)
            assertEquals(numberFieldExpected, text);
        else
            assertEquals(textFieldExcepted, text);
    }

    @Test
    public void uint32() {
        Wire wire = createWire();
        wire.write().uint32(1);
        wire.write(BWKey.field1).uint32(2);
        wire.write(() -> "Test").uint32(3);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 16, cap: 1TiB ] À⒈Æfield1⒉ÄTest⒊",
                "[pos: 0, lim: 28, cap: 1TiB ] À¨⒈٠٠٠Æfield1¨⒉٠٠٠ÄTest¨⒊٠٠٠",
                "[pos: 0, lim: 11, cap: 1TiB ] À⒈¹⒈⒉¹²ñ\u009E⒈⒊",
                "[pos: 0, lim: 23, cap: 1TiB ] À¨⒈٠٠٠¹⒈¨⒉٠٠٠¹²ñ\u009E⒈¨⒊٠٠٠",
                "[pos: 0, lim: 3, cap: 1TiB ] ⒈⒉⒊",
                "[pos: 0, lim: 15, cap: 1TiB ] ¨⒈٠٠٠¨⒉٠٠٠¨⒊٠٠٠");
        checkAsText123(wire);

        // ok as blank matches anything
        AtomicLong i = new AtomicLong();
        IntStream.rangeClosed(1, 3).forEach(e -> {
            wire.read().uint32(i::set);
            assertEquals(e, i.get());
        });

        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();

    }

    @Test
    public void int32() {
        Wire wire = createWire();
        wire.write().int32(1);
        wire.write(BWKey.field1).int32(2);
        wire.write(() -> "Test").int32(3);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 16, cap: 1TiB ] À⒈Æfield1⒉ÄTest⒊",
                "[pos: 0, lim: 28, cap: 1TiB ] À¤⒈٠٠٠Æfield1¤⒉٠٠٠ÄTest¤⒊٠٠٠",
                "[pos: 0, lim: 11, cap: 1TiB ] À⒈¹⒈⒉¹²ñ\u009E⒈⒊",
                "[pos: 0, lim: 23, cap: 1TiB ] À¤⒈٠٠٠¹⒈¤⒉٠٠٠¹²ñ\u009E⒈¤⒊٠٠٠",
                "[pos: 0, lim: 3, cap: 1TiB ] ⒈⒉⒊",
                "[pos: 0, lim: 15, cap: 1TiB ] ¤⒈٠٠٠¤⒉٠٠٠¤⒊٠٠٠");
        checkAsText123(wire);

        // ok as blank matches anything
        AtomicInteger i = new AtomicInteger();
        IntStream.rangeClosed(1, 3).forEach(e -> {
            wire.read().int32(i::set);
            assertEquals(e, i.get());
        });

        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();

    }

    @Test
    public void int64() {
        Wire wire = createWire();
        wire.write().int64(1);
        wire.write(BWKey.field1).int64(2);
        wire.write(() -> "Test").int64(3);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 16, cap: 1TiB ] À⒈Æfield1⒉ÄTest⒊",
                "[pos: 0, lim: 40, cap: 1TiB ] À¥⒈٠٠٠٠٠٠٠Æfield1¥⒉٠٠٠٠٠٠٠ÄTest¥⒊٠٠٠٠٠٠٠",
                "[pos: 0, lim: 11, cap: 1TiB ] À⒈¹⒈⒉¹²ñ\u009E⒈⒊",
                "[pos: 0, lim: 35, cap: 1TiB ] À¥⒈٠٠٠٠٠٠٠¹⒈¥⒉٠٠٠٠٠٠٠¹²ñ\u009E⒈¥⒊٠٠٠٠٠٠٠",
                "[pos: 0, lim: 3, cap: 1TiB ] ⒈⒉⒊",
                "[pos: 0, lim: 27, cap: 1TiB ] ¥⒈٠٠٠٠٠٠٠¥⒉٠٠٠٠٠٠٠¥⒊٠٠٠٠٠٠٠");
        checkAsText123(wire);

        // ok as blank matches anything
        AtomicLong i = new AtomicLong();
        LongStream.rangeClosed(1, 3).forEach(e -> {
            wire.read().int64(i::set);
            assertEquals(e, i.get());
        });

        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();

    }

    @Test
    public void float64() {
        Wire wire = createWire();
        wire.write().float64(1);
        wire.write(BWKey.field1).float64(2);
        wire.write(() -> "Test").float64(3);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 16, cap: 1TiB ] À⒈Æfield1⒉ÄTest⒊",
                "[pos: 0, lim: 40, cap: 1TiB ] À\u0091٠٠٠٠٠٠ð?Æfield1\u0091٠٠٠٠٠٠٠@ÄTest\u0091٠٠٠٠٠٠⒏@",
                "[pos: 0, lim: 11, cap: 1TiB ] À⒈¹⒈⒉¹²ñ\u009E⒈⒊",
                "[pos: 0, lim: 35, cap: 1TiB ] À\u0091٠٠٠٠٠٠ð?¹⒈\u0091٠٠٠٠٠٠٠@¹²ñ\u009E⒈\u0091٠٠٠٠٠٠⒏@",
                "[pos: 0, lim: 3, cap: 1TiB ] ⒈⒉⒊",
                "[pos: 0, lim: 27, cap: 1TiB ] \u0091٠٠٠٠٠٠ð?\u0091٠٠٠٠٠٠٠@\u0091٠٠٠٠٠٠⒏@");
        checkAsText123(wire);

        // ok as blank matches anything
        class Floater {
            double f;

            public void set(double d) {
                f = d;
            }
        }
        Floater n = new Floater();
        IntStream.rangeClosed(1, 3).forEach(e -> {
            wire.read().float64(n::set);
            assertEquals(e, n.f, 0.0);
        });

        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();
    }

    @Test
    public void text() {
        String name = "Long field name which is more than 32 characters, Bye";

        Wire wire = createWire();
        wire.write().text("Hello");
        wire.write(BWKey.field1).text("world");
        wire.write(() -> "Test").text(name);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 80, cap: 1TiB ] ÀåHelloÆfield1åworldÄTest¸5" + name,
                "[pos: 0, lim: 80, cap: 1TiB ] ÀåHelloÆfield1åworldÄTest¸5" + name,
                "[pos: 0, lim: 75, cap: 1TiB ] ÀåHello¹⒈åworld¹²ñ\u009E⒈¸5" + name,
                "[pos: 0, lim: 75, cap: 1TiB ] ÀåHello¹⒈åworld¹²ñ\u009E⒈¸5" + name,
                "[pos: 0, lim: 67, cap: 1TiB ] åHelloåworld¸5" + name,
                "[pos: 0, lim: 67, cap: 1TiB ] åHelloåworld¸5" + name);
        checkAsText(wire, "\"\": Hello\n" +
                        "field1: world\n" +
                        "Test: \"" + name + "\"\n",
                "\"\": Hello\n" +
                        "1: world\n" +
                        "2603186: \"" + name + "\"\n",
                "Hello\n" +
                        "world\n" +
                        "\"" + name + "\"\n");

        // ok as blank matches anything
        StringBuilder sb = new StringBuilder();
        Stream.of("Hello", "world", name).forEach(e -> {
            wire.read().text(sb);
            assertEquals(e, sb.toString());
        });

        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();
    }

    @Test
    public void type() {
        Wire wire = createWire();
        wire.write().type("MyType");
        wire.write(BWKey.field1).type("AlsoMyType");
        String name1 = "com.sun.java.swing.plaf.nimbus.InternalFrameInternalFrameTitlePaneInternalFrameTitlePaneMaximizeButtonWindowNotFocusedState";
        wire.write(() -> "Test").type(name1);
        wire.flip();
        checkWire(wire, "[pos: 0, lim: 158, cap: 1TiB ] À¶⒍MyTypeÆfield1¶⒑AlsoMyTypeÄTest¶{" + name1,
                "[pos: 0, lim: 158, cap: 1TiB ] À¶⒍MyTypeÆfield1¶⒑AlsoMyTypeÄTest¶{" + name1,
                "[pos: 0, lim: 153, cap: 1TiB ] À¶⒍MyType¹⒈¶⒑AlsoMyType¹²ñ\u009E⒈¶{" + name1,
                "[pos: 0, lim: 153, cap: 1TiB ] À¶⒍MyType¹⒈¶⒑AlsoMyType¹²ñ\u009E⒈¶{" + name1,
                "[pos: 0, lim: 145, cap: 1TiB ] ¶⒍MyType¶⒑AlsoMyType¶{" + name1,
                "[pos: 0, lim: 145, cap: 1TiB ] ¶⒍MyType¶⒑AlsoMyType¶{" + name1);
        checkAsText(wire, "\"\": !MyType field1: !AlsoMyType Test: !" + name1,
                "\"\": !MyType 1: !AlsoMyType 2603186: !" + name1,
                "!MyType !AlsoMyType !" + name1);

        // ok as blank matches anything
        StringBuilder sb = new StringBuilder();
        Stream.of("MyType", "AlsoMyType", name1).forEach(e -> {
            wire.read().type(sb);
            assertEquals(e, sb.toString());
        });

        assertEquals(0, bytes.remaining());
        // check it's safe to read too much.
        wire.read();
    }


    @Test
    public void testBytes() {
        Wire wire = createWire();
        byte[] allBytes = new byte[256];
        for (int i = 0; i < 256; i++)
            allBytes[i] = (byte) i;
        wire.write().bytes(NoBytesStore.NO_BYTES)
                .write().bytes(Bytes.wrap("Hello".getBytes()))
                .write().bytes(Bytes.wrap("quotable, text".getBytes()))
                .write().bytes(allBytes);
        wire.flip();
        System.out.println(bytes.toDebugString());
        NativeBytes allBytes2 = nativeBytes();
        wire.read()
                .bytes(b -> assertEquals(0, b.length))
                .read().bytes(b -> assertArrayEquals("Hello".getBytes(), b))
                .read().bytes(b -> assertArrayEquals("quotable, text".getBytes(), b))
                .read().bytes(allBytes2);
        allBytes2.flip();
        assertEquals(Bytes.wrap(allBytes), allBytes2);
    }


    @Test
    public void testWriteMarshallable() {
        Wire wire = createWire();
        MyTypes mtA = new MyTypes();
        mtA.b(true);
        mtA.d(123.456);
        mtA.i(-12345789);
        mtA.s((short) 12345);
        mtA.text.append("Hello World");

        wire.write(() -> "A").marshallable(mtA);

        MyTypes mtB = new MyTypes();
        mtB.b(false);
        mtB.d(123.4567);
        mtB.i(-123457890);
        mtB.s((short) 1234);
        mtB.text.append("Bye now");
        wire.write(() -> "B").marshallable(mtB);

        wire.flip();
//        System.out.println(wire.bytes().toDebugString(400));
        checkWire(wire, "[pos: 0, lim: 144, cap: 1TiB ] ÁA\u0082C٠٠٠ÆB_FLAG¿ÅS_NUM§90ÅD_NUM\u0091w¾\u009F\u001A/Ý^@ÅL_NUM٠ÅI_NUM¤C\u009ECÿÄTEXTëHello WorldÁB\u0082?٠٠٠ÆB_FLAG¾ÅS_NUM§Ò⒋ÅD_NUM\u0091S⒌£\u0092:Ý^@ÅL_NUM٠ÅI_NUM¤\u009E.¤øÄTEXTçBye now",
                "[pos: 0, lim: 160, cap: 1TiB ] ÁA\u0082K٠٠٠ÆB_FLAG¿ÅS_NUM£90ÅD_NUM\u0091w¾\u009F\u001A/Ý^@ÅL_NUM¥٠٠٠٠٠٠٠٠ÅI_NUM¤C\u009ECÿÄTEXTëHello WorldÁB\u0082G٠٠٠ÆB_FLAG¾ÅS_NUM£Ò⒋ÅD_NUM\u0091S⒌£\u0092:Ý^@ÅL_NUM¥٠٠٠٠٠٠٠٠ÅI_NUM¤\u009E.¤øÄTEXTçBye now",
                "[pos: 0, lim: 96, cap: 1TiB ] ¹A\u0082+٠٠٠¹٠¿¹⒈§90¹⒉\u0091w¾\u009F\u001A/Ý^@¹⒊٠¹⒋¤C\u009ECÿ¹⒌ëHello World¹B\u0082'٠٠٠¹٠¾¹⒈§Ò⒋¹⒉\u0091S⒌£\u0092:Ý^@¹⒊٠¹⒋¤\u009E.¤ø¹⒌çBye now",
                "[pos: 0, lim: 112, cap: 1TiB ] ¹A\u00823٠٠٠¹٠¿¹⒈£90¹⒉\u0091w¾\u009F\u001A/Ý^@¹⒊¥٠٠٠٠٠٠٠٠¹⒋¤C\u009ECÿ¹⒌ëHello World¹B\u0082/٠٠٠¹٠¾¹⒈£Ò⒋¹⒉\u0091S⒌£\u0092:Ý^@¹⒊¥٠٠٠٠٠٠٠٠¹⒋¤\u009E.¤ø¹⒌çBye now",
                "[pos: 0, lim: 68, cap: 1TiB ] \u0082\u001F٠٠٠¿§90\u0091w¾\u009F\u001A/Ý^@٠¤C\u009ECÿëHello World\u0082\u001B٠٠٠¾§Ò⒋\u0091S⒌£\u0092:Ý^@٠¤\u009E.¤øçBye now",
                "[pos: 0, lim: 84, cap: 1TiB ] \u0082'٠٠٠¿£90\u0091w¾\u009F\u001A/Ý^@¥٠٠٠٠٠٠٠٠¤C\u009ECÿëHello World\u0082#٠٠٠¾£Ò⒋\u0091S⒌£\u0092:Ý^@¥٠٠٠٠٠٠٠٠¤\u009E.¤øçBye now");
        MyTypes mt2 = new MyTypes();
        wire.read(() -> "A").marshallable(mt2);
        assertEquals(mt2, mtA);

        wire.read(() -> "B").marshallable(mt2);
        assertEquals(mt2, mtB);
    }

}