/*
 *  Copyright 2009-2012 Michael Dalton
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package jtaint;

import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import java.util.BitSet;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;

import java.sql.SQLException;

public class HypersonicSqlValidatorTest
{

    private final int maxlen;
    private final SafeRandom sr;
    private final HypersonicSqlValidator v;
    private boolean exception;

    public HypersonicSqlValidatorTest(int maxlen, SafeRandom sr) {
        this.maxlen = maxlen;
        this.sr = sr;
        this.v = new HypersonicSqlValidator();
        clearException();
    }

    /* Can't assume enum support -- so we're going to do this the hard way */
    static class QueryToken {
        static final int Q_SINGLELIT     = 0; /* String Literal - 'foo' */
        static final int Q_QUOTEID       = 1; /* Quoted Identifier - "foo" */
        static final int Q_NUMBER        = 2; /* Number */
        static final int Q_LINECOMM      = 3; /* Line Comment */
        static final int Q_BLKCOMM       = 4; /* Block Comment */
        static final int Q_OTHER         = 5; /* Operators, Identifiers, etc. */
        static final int Q_BOOLEAN       = 6; /* Boolean Literal */
        static final int Q_END           = 7;

        static String[] qMethod = 
        {
            "SingleQuoteLiteral",
            "QuotedIdentifier",
            "Number",
            "LineComment",
            "BlockComment",
            "Other",
            "Boolean"
        };

        static String qChars = "abcdefghijklmnopqrstuvwxyz" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
            "0123456789`';,/[]-=~!@#$%^&*()_+{}:\"/,.<>?";

        public static char randChar(SafeRandom sr) { 
            return qChars.charAt(sr.nextInt(qChars.length()));
        }
    }

    public boolean pendingException() { return exception; }
    public void clearException() { exception = false; }
    public void setException() { exception = true; }

    
    private void appendSingleQuoteLiteral(@StringBuilder@ query) {
        int len = sr.nextInt(256); 
        StringBuffer sb = new StringBuffer(len);
        BitSet b = new BitSet();
        String s;

        char qc = ' ';
       
        if (query.length() > 0)
            qc = query.charAt(query.length() - 1);

        if (qc == '\'')
            sb.append(' ');

        sb.append('\'');

        for (int i = 0; i < sb.length(); i++) {
            if (sr.nextInt(256) == 0) {
                b.set(i);
                setException();
            }
        }

        for (int i = sb.length(); i < len + 1; i++) {
            int oldlen = sb.length();
            boolean taint = sr.nextBoolean();
            int c;
            int clen;
        
            if (sr.nextInt(12) == 0) 
               //[ifJava5+] 
                c = Character.MIN_CODE_POINT + sr.nextInt(
                        Character.MAX_CODE_POINT - Character.MIN_CODE_POINT);
               //[fiJava5+]
               //[ifJava4]
               c = sr.nextInt(0xffff + 1);
               //[fiJava4]
            else 
                c = QueryToken.randChar(sr);

            //[ifJava5+]
            sb.append(Character.toChars(c));
            //[fiJava5+]
            //[ifJava4]
            sb.append((char)c);
            //[fiJava4]

            if (c == '\0' && taint)
                setException();

            if (c == '\'') {
                if (!taint) {
                    sb.append('\'');
                    if (sr.nextInt(256) == 0) {
                        b.set(oldlen+1);
                        setException();
                    }
                } else {
                    sb.append('\'');
                    if (sr.nextInt(256) != 0) {
                        b.set(oldlen+1);
                    } else { 
                        setException();
                    }
                }
            } 

            //[ifJava5+]
            clen = Character.charCount(c);
            for (int j = 0; j < clen; j++)
                b.set(oldlen + j, taint);
            //[fiJava5+]
            //[ifJava4]
            b.set(oldlen, taint);
            //[fiJava4]
        }

        sb.append('\'');
        if (sr.nextInt(256) == 0) {
            b.set(sb.length() - 1);
            setException();
        }

        s = new String(sb.toString(), new Taint(b, sb.length()));
        query.append(s);
    }

    /* We can't use enums due to Java 1.4 support. These are the states
     * used by the state machine in appendBlockComment
     */

    private static final int DEFAULT = 0,
                             SEEN_SLASH = 1,
                             SEEN_STAR = 2;

    private void appendBlockComment(@StringBuilder@ query, boolean nested) {
        int len = sr.nextInt(64); 
        

        StringBuffer sb = new StringBuffer(len);
        String s;
        BitSet b = new BitSet();

        int level = 1,
            state = DEFAULT;

        sb.append("/*");

        for (int i = 0; level > 0 && i < len; i++) {
            int oldlen = sb.length();
            boolean taint = sr.nextBoolean();
            int c;
            int clen;
            int nextState;
         
            if (sr.nextInt(12) == 0) 
               //[ifJava5+] 
                c = Character.MIN_CODE_POINT + sr.nextInt(
                        Character.MAX_CODE_POINT - Character.MIN_CODE_POINT);
               //[fiJava5+]
               //[ifJava4]
               c = sr.nextInt(0xffff + 1);
               //[fiJava4]
            else 
                c = QueryToken.randChar(sr);

            //[ifJava5+]
            clen = Character.charCount(c);
            //[fiJava5+]
            //[ifJava4]
            clen = 1;
            //[fiJava4]

            if (c == '*')
                nextState = SEEN_STAR;
            else if (c == '/')
                nextState = SEEN_SLASH;
            else
                nextState = DEFAULT;

            if (nested && state == SEEN_SLASH && c == '*') {
                level++;
                if (taint || b.get(oldlen-1))
                    setException();
                nextState = DEFAULT;
            } else if (state == SEEN_STAR && c == '/') {
                level--;
                if (taint || b.get(oldlen-1))
                    setException();
                nextState = DEFAULT;
            } 

            //[ifJava5+]
            sb.append(Character.toChars(c));
            //[fiJava5+]
            //[ifJava4]
            sb.append((char)c);
            //[fiJava4]
            for (int j = 0; j < clen; j++)
                b.set(oldlen + j, taint);
            state = nextState;
        }

        for (int i = 0; i < level; i++)
            sb.append(" */");

        s = new String(sb.toString(), new Taint(b, sb.length()));
        query.append(s);
    }

    private void appendLineComment(@StringBuilder@ query) {
        int i, len = 1 + sr.nextInt(64); 
        StringBuffer sb = new StringBuffer(len);
        BitSet b = new BitSet();
        String s;

        if (sr.nextBoolean())
            sb.append("--");
        else
            sb.append("//");

        for (i = 0; i < len; i++) {
            int oldlen = sb.length();
            boolean taint = sr.nextBoolean();
            int c;
            int clen;
         
            if (sr.nextInt(12) == 0) 
               //[ifJava5+] 
                c = Character.MIN_CODE_POINT + sr.nextInt(
                        Character.MAX_CODE_POINT - Character.MIN_CODE_POINT);
               //[fiJava5+]
               //[ifJava4]
               c = sr.nextInt(0xffff + 1);
               //[fiJava4]
            else 
                c = QueryToken.randChar(sr);

            //[ifJava5+]
            clen = Character.charCount(c);
            //[fiJava5+]
            //[ifJava4]
            clen = 1;
            //[fiJava4]

            if (taint && (c == '\r' || c == '\n'))
                setException();

            //[ifJava5+]
            sb.append(Character.toChars(c));
            //[fiJava5+]
            //[ifJava4]
            sb.append((char)c);
            //[fiJava4]
            
            for (int j = 0; j < clen; j++)
                b.set(oldlen + j, taint);

            if (c == '\r' || c == '\n')
                break;
        }

        if (i == len) {
            if (sr.nextBoolean())
                sb.append('\r');
            else
                sb.append('\n');
        }

        s = new String(sb.toString(), new Taint(b, sb.length()));
        query.append(s);
    }

    private void appendBoolean(@StringBuilder@ query) {
        String s;
        BitSet b = new BitSet();
        boolean taint = sr.nextBoolean();
        int oldlen = query.length();
        
        if (oldlen != 0 
               && !v.isSqlWhitespace(query.charAt(oldlen - 1))
               && !v.isSqlOperator(query.charAt(oldlen - 1))
               && !v.isSqlSpecial(query.charAt(oldlen - 1))
               && taint) {
            if (sr.nextInt(256) == 0)
                setException();
            else 
                query.append(' ');
        }

        if (sr.nextBoolean()) 
            s = "true";
        else
            s = "false";

        @StringBuilder@ sb = new @StringBuilder@(s.length());
        
        for (int i = 0; i < s.length(); i++) {
            if (sr.nextInt(128) == 0) {
                /* Create a partially tainted boolean literal */
                if (!taint) 
                    b.set(i);
            } else if (taint) {
                b.set(i);
            }

            if (sr.nextBoolean())
                sb.append(Character.toUpperCase(s.charAt(i)));
            else
                sb.append(s.charAt(i));
        }

        /* Partially tainted boolean literals are forbidden */
        if (b.cardinality() != 0 && b.cardinality() != s.length())
            setException();

        query.append(new String(sb.toString(), new Taint(b, sb.length())));
    }

    private void appendOther(@StringBuilder@ query)
    {
        int len = 1 + sr.nextInt(64),
            origLength = query.length(); 
        StringBuffer sb = new StringBuffer(len);
        BitSet b = new BitSet();
        String s;
               
        for (int i = 0; i < len; i++) {
            int oldlen = sb.length();
            boolean taint = sr.nextInt(64) == 0;
            int c, clen;
            char prevc;
         
            if (sr.nextInt(12) == 0) 
               //[ifJava5+] 
                c = Character.MIN_CODE_POINT + sr.nextInt(
                        Character.MAX_CODE_POINT - Character.MIN_CODE_POINT);
               //[fiJava5+]
               //[ifJava4]
               c = sr.nextInt(0xffff + 1);
               //[fiJava4]
            else 
                c = QueryToken.randChar(sr);

            //[ifJava5+]
            clen = Character.charCount(c);
            //[fiJava5+]
            //[ifJava4]
            clen = 1;
            //[fiJava4]

            /* Ensure we aren't creating a boolean literal */
            String q = sb.toString();
            if ((q.regionMatches(true, sb.length() - 3, "tru", 0, 3) 
                    || q.regionMatches(true, sb.length() - 4, "fals", 0, 4))
                    && (c == 'e' || c == 'E')) {
                i--;
                continue;
            }

            if (sb.length() > 0)
                prevc = sb.charAt(sb.length()-1);
            else if (query.length() > 0)
                prevc = query.charAt(query.length()-1);
            else
                prevc = ' ';

            /* Untainted ' or " are handled by SingleQ and QuoteID functions */
            if (c == '\'' || c == '"') { i--; continue; }

            /* Similarly, untainted '/' or '*' that result in comments must
             * be avoided and handled by the LineComment/BlockComment fns  
             */
            else if (prevc == '*' && c == '/') { i--; continue; }

            else if (prevc == '/' && c == '*') { i--; continue; }

            /* Do not create a line comment */
            else if (prevc == '-' && c == '-') { i--; continue; }
            else if (prevc == '/' && c == '/') { i--; continue; }
           
            //[ifJava5+]
            sb.append(Character.toChars(c));
            //[fiJava5+]
            //[ifJava4]
            sb.append((char)c);
            //[fiJava4]
            for (int j = 0; j < clen; j++)
                b.set(oldlen + j, taint);


        }

        char lastc = sb.charAt(sb.length() - 1);
        /* Ensure that we do not end in a valid dollar tag or comment, or
         * a tainted string literal */
        if (lastc == '/' || lastc == '*' || lastc == '-'
                || b.get(sb.length()-1) || (lastc >= '0' && lastc <= '9'))
            sb.append(' ');

        s = new String(sb.toString(), new Taint(b, sb.length()));
        query.append(s);
        

        if (!s.@internal@isTainted())
            return;

        /* Any tainted character will cause a security exception unless all
         * tainted substrings are valid numeric literals
         */
        for (int offset = origLength; offset < query.length(); offset++)
        {
            int toff = 0;

            if (!b.get(offset-origLength)) continue;
            if (pendingException()) break;

            try {
               toff = SqlParseUtil.parseTaintedValue(query.toString(),offset,v);
            } catch (JTaintException e) {
                setException();
            } 

            if (toff > 0) 
                offset = toff;
        }
    }

    private void appendQuotedIdentifier(@StringBuilder@ query)
    {
        int len = sr.nextInt(64); 
        StringBuffer sb = new StringBuffer(len+2);
        BitSet b = new BitSet();
        String s;

        sb.append('"');
        for (int i = 1; i < len + 1; i++) {
            int c, clen;
            int oldlen = sb.length();
            boolean taint;

            if (sr.nextInt(12) == 0) 
               //[ifJava5+] 
                c = Character.MIN_CODE_POINT + sr.nextInt(
                        Character.MAX_CODE_POINT - Character.MIN_CODE_POINT);
               //[fiJava5+]
               //[ifJava4]
               c = sr.nextInt(0xffff + 1);
               //[fiJava4]
            else 
                c = QueryToken.randChar(sr);

            //[ifJava5+]
            clen = Character.charCount(c);
            //[fiJava5+]
            //[ifJava4]
            clen = 1;
            //[fiJava4]
            
            taint = sr.nextInt(1024) == 0;
            if (taint)
                setException();

            if (c == '"')
                sb.append('"');

            //[ifJava5+]
            sb.append(Character.toChars(c));
            //[fiJava5+]
            //[ifJava4]
            sb.append((char)c);
            //[fiJava4]
            for (int j = 0; j < clen; j++)
                b.set(oldlen + j, taint);
        }

        sb.append('"');

        sb.append(" "); /* If the next Token begins with a tainted '"', avoid
                         * a security exception
                         */

        s = new String(sb.toString(), new Taint(b, sb.length()));
        query.append(s);
    }

    private void appendNumber(@StringBuilder@ query) {
        String digit = "0123456789";
        int len = 1 + sr.nextInt(32),
            oldlen = query.length(); 
        StringBuffer sb = new StringBuffer();
        boolean taint = sr.nextBoolean();
        String s;

        if (oldlen != 0 
               && !v.isSqlWhitespace(query.charAt(oldlen - 1))
               && !v.isSqlOperator(query.charAt(oldlen - 1))
               && !v.isSqlSpecial(query.charAt(oldlen - 1))
               && taint) {
            if (sr.nextInt(256) == 0)
                setException();
            else {
                query.append(' ');
                oldlen++;
            }
        }

        if (sr.nextBoolean()) {
            if (sr.nextBoolean())
                sb.append('+');
            else
                sb.append('-');
        }

        if (sr.nextBoolean()) { /* leading digits */
            for (int i = 0; i < len; i++) {
                sb.append(digit.charAt(sr.nextInt(digit.length())));
            }
            if (sr.nextBoolean()) {
                sb.append('.');
                if (sr.nextBoolean()) {
                    int len2 = sr.nextInt(32); 
                    for (int j = 0; j < len2; j++) {
                        sb.append(digit.charAt(sr.nextInt(digit.length())));
                    }
                }
            }

        } else {
            sb.append('.');
            for (int i = 0; i < len; i++) 
                        sb.append(digit.charAt(sr.nextInt(digit.length())));
        }

        if (sr.nextBoolean()) {
            int len3 = 1 + sr.nextInt(32); 
            if (sr.nextBoolean()) 
                sb.append('e');
            else
                sb.append('E');

            if (sr.nextBoolean()) {
                if (sr.nextBoolean())
                    sb.append('+');
                else
                    sb.append('-');
            }

            for (int k = 0; k < len3; k++)
                        sb.append(digit.charAt(sr.nextInt(digit.length())));
        }

        sb.append(' ');
        Taint t = new Taint(taint, sb.length());
        t.clear(sb.length()-1);
        s = new String(sb.toString(), t);
        query.append(s);
    }

    public void appendRandomToken(@StringBuilder@ query, List opList)
    {
        int op = sr.nextInt(QueryToken.Q_END);

        if (opList != null)
            opList.add(QueryToken.qMethod[op]);

        switch(op) {
            case QueryToken.Q_SINGLELIT:
                appendSingleQuoteLiteral(query);
                break;

            case QueryToken.Q_QUOTEID:
                appendQuotedIdentifier(query);
                break;

            case QueryToken.Q_NUMBER: 
                appendNumber(query); 
                break;

            case QueryToken.Q_LINECOMM:
                appendLineComment(query);
                break;

            case QueryToken.Q_BLKCOMM:
                appendBlockComment(query, false);
                break;
            
            case QueryToken.Q_OTHER: 
                appendOther(query);
                break;

            case QueryToken.Q_BOOLEAN:
                appendBoolean(query);
                break;

            default: 
                throw new RuntimeException("switch"); 
        }
    }

    public void test() {
        int len = 1 + sr.nextInt(maxlen-1);
        
        List opList = new ArrayList();
        @StringBuilder@ query = new @StringBuilder@();
        clearException(); 

        while(query.length() < len) {
            @StringBuilder@ oldQuery = new @StringBuilder@(query.toString());
            Throwable e = null;
            appendRandomToken(query, opList);

            try {
                v.validateSqlQuery(query.toString());
            } catch (JTaintException s) {
                if (pendingException()) {
                    clearException();
                    if (query.toString().indexOf(0) != -1)
                        Log.clearWarning();
                    query = oldQuery;
                    opList.add("Exception");
                    continue;
                } else {
                    e = s;
                }
            } catch(Throwable th) {
                e = th;
            }

            /* Suppress null byte in SQL query warnings */
            if (query.toString().indexOf(0) != -1)
                Log.clearWarning();

            if (e != null || pendingException()) {
                String[] ops = new String[opList.size()];
                opList.toArray(ops);

                System.out.println("FAILURE-- query: " + query);
                System.out.println("Taint " + 
                                 query.toString().@internal@taint().toString());

                for (int j = 0; j < ops.length; j++)
                    System.out.println("op " + j + ": " + ops[j]);
                if (pendingException())
                    System.out.println("FAILURE: did not get exception");
                else {
                    System.out.println("FAILURE " + e);
                    e.printStackTrace();
                }
                System.exit(-1);
            }
        }
    }

    public static void main(String[] args) {
        long seed = System.currentTimeMillis();
        int maxlen = 65536; 
        int nrtest = 16384;

        SafeRandom sr;
        HypersonicSqlValidatorTest qt;
        String logfile = "HypersonicSqlValidatorTest.log";
        PrintStream ps = null;

        for (int i = 0; i < args.length; i++) {
           if (args[i].equals("-s"))
               seed = Long.decode(args[++i]).longValue();
           else if (args[i].equals("-l"))
               maxlen = Integer.decode(args[++i]).intValue();
           else if (args[i].equals("-n"))
               nrtest = Integer.decode(args[++i]).intValue();
           else if (args[i].equals("-f"))
               logfile = args[++i];
           else {
               System.out.println("Usage: java HypersonicSqlValidatorTest "
                       + "[-s randomSeed] "
                       + "[-l maximumLengthofQuery] "
                       + "[-n NumberofTests]"
                       + "[-f logFileName]");
               System.exit(-1);
           }
        }

        try {
            ps = new PrintStream(new FileOutputStream(logfile));
        } catch (FileNotFoundException e) {
            System.out.println("Error opening logfile [" + logfile + "]: " + e);
            System.exit(-1);
        }

        ps.print("-s ");
        ps.print(seed);
        ps.print(" -l ");
        ps.print(maxlen);
        ps.print(" -n ");
        ps.print(nrtest);
        ps.print(" -f ");
        ps.print(logfile + "\n");
        ps.flush();
        ps.close();

        sr = new SafeRandom(seed);
        qt = new HypersonicSqlValidatorTest(maxlen, sr);

        for (int i = 0; i < nrtest; i++) {
                qt.test();
        }

        if (Log.hasError() || Log.hasWarning() || Log.hasVuln()) {
            System.out.println("Error or Warning encountered -- check logs");
            System.exit(-1);
        }
     }
}
