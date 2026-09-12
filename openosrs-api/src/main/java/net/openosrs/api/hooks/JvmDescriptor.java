package net.openosrs.api.hooks;

/** Strict descriptor syntax checks without resolving or initializing game classes. */
public final class JvmDescriptor
{
    private JvmDescriptor() { }

    public static boolean isMethod(String value)
    {
        if (value == null || value.length() < 3 || value.charAt(0) != '(') return false;
        int cursor = 1;
        int slots = 0;
        while (cursor < value.length() && value.charAt(cursor) != ')')
        {
            char first = value.charAt(cursor);
            slots += first == 'J' || first == 'D' ? 2 : 1;
            cursor = typeEnd(value, cursor);
            if (cursor < 0 || slots > 255) return false;
        }
        if (++cursor >= value.length()) return false;
        return value.charAt(cursor) == 'V' ? cursor + 1 == value.length()
            : typeEnd(value, cursor) == value.length();
    }

    private static int typeEnd(String value, int cursor)
    {
        int dimensions = 0;
        while (cursor < value.length() && value.charAt(cursor) == '[')
        {
            cursor++;
            if (++dimensions > 255) return -1;
        }
        if (cursor >= value.length()) return -1;
        char type = value.charAt(cursor++);
        if ("BCDFIJSZ".indexOf(type) >= 0) return cursor;
        if (type != 'L') return -1;
        int end = value.indexOf(';', cursor);
        if (end <= cursor) return -1;
        String name = value.substring(cursor, end);
        if (name.startsWith("/") || name.endsWith("/") || name.contains("//")) return -1;
        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (c == '.' || c == '[' || c == '(' || c == ')' || Character.isWhitespace(c)) return -1;
        }
        return end + 1;
    }
}
