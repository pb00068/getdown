//
// Getdown - application installer, patcher and launcher
// Copyright (C) 2004-2018 Getdown authors
// https://github.com/threerings/getdown/blob/master/LICENSE

/*
 * @(#)JarDiff.java 1.7 05/11/17
 *
 * Copyright (c) 2006 Sun Microsystems, Inc. All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * -Redistribution of source code must retain the above copyright notice, this
 *  list of conditions and the following disclaimer.
 *
 * -Redistribution in binary form must reproduce the above copyright notice,
 *  this list of conditions and the following disclaimer in the documentation
 *  and/or other materials provided with the distribution.
 *
 * Neither the name of Sun Microsystems, Inc. or the names of contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES, INCLUDING
 * ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
 * OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN MIDROSYSTEMS, INC. ("SUN")
 * AND ITS LICENSORS SHALL NOT BE LIABLE FOR ANY DAMAGES SUFFERED BY LICENSEE
 * AS A RESULT OF USING, MODIFYING OR DISTRIBUTING THIS SOFTWARE OR ITS
 * DERIVATIVES. IN NO EVENT WILL SUN OR ITS LICENSORS BE LIABLE FOR ANY LOST
 * REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL,
 * INCIDENTAL OR PUNITIVE DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY
 * OF LIABILITY, ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE,
 * EVEN IF SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 *
 * You acknowledge that this software is not designed, licensed or intended
 * for use in the design, construction, operation or maintenance of any
 * nuclear facility.
 */

package com.threerings.getdown.tools;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * JarDiff is able to create a zip file containing the delta between two jar or zip files (old
 * and new). The delta file can then be applied to the old archive file to reconstruct the new
 * archive file.
 *
 * <p> Refer to the JNLP spec for details on how this is done.
 *
 * @version 1.13, 06/26/03
 */
public class JarDiff implements JarDiffCodes
{
    private static final int DEFAULT_READ_SIZE = 2048;
    private static final byte[] newBytes = new byte[DEFAULT_READ_SIZE];
    private static final byte[] oldBytes = new byte[DEFAULT_READ_SIZE];

    // The JARDiff.java is the stand-alone jardiff.jar tool. Thus, we do not depend on Globals.java
    // and other stuff here. Instead, we use an explicit _debug flag.
    private static boolean _debug;

    /**
     * Creates a patch from the two passed in files, writing the result to {@code os}.
     */
    public static void createPatch (String oldPath, String newPath,
                                    OutputStream os, boolean minimal) throws IOException
    {
        try (ZipFile2 oldArchive = new ZipFile2(oldPath);
             ZipFile2 newArchive = new ZipFile2(newPath)) {

            Map<String,String> moved = new HashMap<>();
            Set<String> implicit = new HashSet<>();
            Set<String> moveSrc = new HashSet<>();
            Set<String> newEntries = new HashSet<>();

            // FIRST PASS
            // Go through the entries in new archive and determine which files are candidates for
            // implicit moves (files that have the same filename and same content in old and new)
            // and for files that cannot be implicitly moved, we will either find out whether it is
            // moved or new (modified)
            for (ZipEntry newEntry : newArchive) {
                String newname = newEntry.getName();

                // Return best match of contents, will return a name match if possible
                String oldname = oldArchive.getBestMatch(newArchive, newEntry);
                if (oldname == null) {
                    // New or modified entry
                    if (_debug) {
                        System.out.println("NEW: "+ newname);
                    }
                    newEntries.add(newname);
                } else {
                    // Content already exist - need to do a move

                    // Should do implicit move? Yes, if names are the same, and
                    // no move command already exist from oldArchive
                    if (oldname.equals(newname) && !moveSrc.contains(oldname)) {
                        if (_debug) {
                            System.out.println(newname + " added to implicit set!");
                        }
                        implicit.add(newname);
                    } else {
                        // The 1.0.1/1.0 JarDiffPatcher cannot handle
                        // multiple MOVE command with same src.
                        // The work around here is if we are going to generate
                        // a MOVE command with duplicate src, we will
                        // instead add the target as a new file.  This way
                        // the jardiff can be applied by 1.0.1/1.0
                        // JarDiffPatcher also.
                        if (!minimal && (implicit.contains(oldname) ||
                                         moveSrc.contains(oldname) )) {
                            // generate non-minimal jardiff
                            // for backward compatibility
                            if (_debug) {
                                System.out.println("NEW: "+ newname);
                            }
                            newEntries.add(newname);
                        } else {
                            // Use newname as key, since they are unique
                            if (_debug) {
                                System.err.println("moved.put " + newname + " " + oldname);
                            }
                            moved.put(newname, oldname);
                            moveSrc.add(oldname);
                        }
                        // Check if this disables an implicit 'move <oldname> <oldname>'
                        if (implicit.contains(oldname) && minimal) {

                            if (_debug) {
                                System.err.println("implicit.remove " + oldname);

                                System.err.println("moved.put " + oldname + " " + oldname);

                            }
                            implicit.remove(oldname);
                            moved.put(oldname, oldname);
                            moveSrc.add(oldname);
                        }
                    }
                }
            }

            // SECOND PASS: <deleted files> = <oldjarnames> - <implicitmoves> -
            // <source of move commands> - <new or modified entries>
            List<String> deleted = new ArrayList<>();
            for (ZipEntry oldEntry : oldArchive) {
                String oldName = oldEntry.getName();
                if (!implicit.contains(oldName) && !moveSrc.contains(oldName)
                    && !newEntries.contains(oldName)) {
                    if (_debug) {
                        System.err.println("deleted.add " + oldName);
                    }
                    deleted.add(oldName);
                }
            }

            //DEBUG
            if (_debug) {
                //DEBUG:  print out moved map
                System.out.println("MOVED MAP!!!");
                for (Map.Entry<String,String> entry : moved.entrySet()) {
                    System.out.println(entry);
                }

                //DEBUG:  print out IMOVE map
                System.out.println("IMOVE MAP!!!");
                for (String newName : implicit) {
                    System.out.println("key is " + newName);
                }
            }

            ZipOutputStream jos = new ZipOutputStream(os);

            // Write out all the MOVEs and REMOVEs
            createIndex(jos, deleted, moved);

            // Put in New and Modified entries
            for (String newName : newEntries) {
                if (_debug) {
                    System.out.println("New File: " + newName);
                }
                writeEntry(jos, newArchive.getEntryByName(newName), newArchive);
            }

            jos.finish();
//            jos.close();
        }
    }

    /**
     * Writes the index file out to {@code jos}.
     * {@code oldEntries} gives the names of the files that were removed,
     * {@code movedMap} maps from the new name to the old name.
     */
    private static void createIndex (ZipOutputStream jos, List<String> oldEntries,
                                     Map<String,String> movedMap)
        throws IOException
    {
        StringWriter writer = new StringWriter();
        writer.write(VERSION_HEADER);
        writer.write("\r\n");

        // Write out entries that have been removed
        for (String name : oldEntries) {
            writer.write(REMOVE_COMMAND);
            writer.write(" ");
            writeEscapedString(writer, name);
            writer.write("\r\n");
        }

        // And those that have moved
        for (Map.Entry<String, String> entry : movedMap.entrySet()) {
            String oldName = entry.getValue();
            writer.write(MOVE_COMMAND);
            writer.write(" ");
            writeEscapedString(writer, oldName);
            writer.write(" ");
            writeEscapedString(writer, entry.getKey());
            writer.write("\r\n");
        }

        jos.putNextEntry(new ZipEntry(INDEX_NAME));
        byte[] bytes = writer.toString().getBytes(UTF_8);
        jos.write(bytes, 0, bytes.length);
    }

    protected static Writer writeEscapedString (Writer writer, String string)
        throws IOException
    {
        int index = 0;
        int last = 0;
        char[] chars = null;

        while ((index = string.indexOf(' ', index)) != -1) {
            if (last != index) {
                if (chars == null) {
                    chars = string.toCharArray();
                }
                writer.write(chars, last, index - last);
            }
            last = index;
            index++;
            writer.write('\\');
        }
        if (last != 0 && chars != null) {
            writer.write(chars, last, chars.length - last);
        }
        else {
            // no spaces
            writer.write(string);
        }

        return writer;
    }

    private static void writeEntry (ZipOutputStream jos, ZipEntry entry, ZipFile2 file)
        throws IOException
    {
        try (InputStream data = file.getArchive().getInputStream(entry)) {
            jos.putNextEntry(entry);
            int size = data.read(newBytes);
            while (size != -1) {
                jos.write(newBytes, 0, size);
                size = data.read(newBytes);
            }
        }
    }

    /**
     * ZipFile2 wraps a ZipFile providing some convenience methods.
     */
    private static class ZipFile2 implements Iterable<ZipEntry>, Closeable
    {
        private final ZipFile _archive;
        private List<ZipEntry> _entries;
        private HashMap<String,ZipEntry> _nameToEntryMap;
        private HashMap<Long,LinkedList<ZipEntry>> _crcToEntryMap;

        public ZipFile2 (String path) throws IOException {
            _archive = new ZipFile(new File(path));
            index();
        }

        public ZipFile getArchive () {
            return _archive;
        }

        // from interface Iterable<ZipEntry>
        @Override
        public Iterator<ZipEntry> iterator () {
            return _entries.iterator();
        }

        public ZipEntry getEntryByName (String name) {
            return _nameToEntryMap.get(name);
        }

        /**
         * Returns true if the two InputStreams differ.
         */
        private static boolean differs (InputStream oldIS, InputStream newIS) throws IOException {
            int newSize = 0;
            int oldSize;
            int total = 0;
            boolean retVal = false;

            while (newSize != -1) {
                newSize = newIS.read(newBytes);
                oldSize = oldIS.read(oldBytes);

                if (newSize != oldSize) {
                    if (_debug) {
                        System.out.println("\tread sizes differ: " + newSize +
                                           " " + oldSize + " total " + total);
                    }
                    retVal = true;
                    break;
                }
                if (newSize > 0) {
                    while (--newSize >= 0) {
                        total++;
                        if (newBytes[newSize] != oldBytes[newSize]) {
                            if (_debug) {
                                System.out.println("\tbytes differ at " +
                                                   total);
                            }
                            retVal = true;
                            break;
                        }
                        if ( retVal ) {
                            //Jump out
                            break;
                        }
                        newSize = 0;
                    }
                }
            }

            return retVal;
        }

        public String getBestMatch (ZipFile2 file, ZipEntry entry) throws IOException {
            // check for same name and same content, return name if found
            if (contains(file, entry)) {
                return (entry.getName());
            }

            // return name of same content file or null
            return (hasSameContent(file,entry));
        }

        public boolean contains (ZipFile2 f, ZipEntry e) throws IOException {
            ZipEntry thisEntry = getEntryByName(e.getName());

            // Look up name in 'this' ZipFile2 - if not exist return false
            if (thisEntry == null)
                return false;

            // Check CRC - if no match - return false
            if (thisEntry.getCrc() != e.getCrc())
                return false;

            // Check contents - if no match - return false
            try (InputStream oldIS = getArchive().getInputStream(thisEntry);
                 InputStream newIS = f.getArchive().getInputStream(e)) {
                return !differs(oldIS, newIS);
            }
        }

        public String hasSameContent (ZipFile2 file, ZipEntry entry) throws IOException {
            String thisName = null;
            Long crcL = entry.getCrc();
            // check if this archive contains files with the passed in entry's crc
            if (_crcToEntryMap.containsKey(crcL)) {
                // get the Linked List with files with the crc
                LinkedList<ZipEntry> ll = _crcToEntryMap.get(crcL);
                // go through the list and check for content match
                ListIterator<ZipEntry> li = ll.listIterator(0);
                while (li.hasNext()) {
                    ZipEntry thisEntry = li.next();
                    // check for content match
                    try (InputStream oldIS = getArchive().getInputStream(thisEntry);
                         InputStream newIS = file.getArchive().getInputStream(entry)) {
                        if (!differs(oldIS, newIS)) {
                            thisName = thisEntry.getName();
                            return thisName;
                        }
                    }
                }
            }
            return thisName;
        }

        private void index () throws IOException {
            Enumeration<? extends ZipEntry> entries = _archive.entries();

            _nameToEntryMap = new HashMap<>();
            _crcToEntryMap = new HashMap<>();
            _entries = new ArrayList<>();
            if (_debug) {
                System.out.println("indexing: " + _archive.getName());
            }
            if (entries != null) {
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    long crc = entry.getCrc();
                    Long crcL = crc;
                    if (_debug) {
                        System.out.println("\t" + entry.getName() + " CRC " + crc);
                    }

                    _nameToEntryMap.put(entry.getName(), entry);
                    _entries.add(entry);

                    // generate the CRC to entries map
                    if (_crcToEntryMap.containsKey(crcL)) {
                        // key exist, add the entry to the correcponding linked list
                        LinkedList<ZipEntry> ll = _crcToEntryMap.get(crcL);
                        ll.add(entry);
                        _crcToEntryMap.put(crcL, ll);

                    } else {
                        // create a new entry in the hashmap for the new key
                        LinkedList<ZipEntry> ll = new LinkedList<>();
                        ll.add(entry);
                        _crcToEntryMap.put(crcL, ll);
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            _archive.close();
        }
    }
}
