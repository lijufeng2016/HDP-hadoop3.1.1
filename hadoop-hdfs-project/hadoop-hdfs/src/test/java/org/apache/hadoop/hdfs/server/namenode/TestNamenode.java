package org.apache.hadoop.hdfs.server.namenode;

import org.junit.Test;

public class TestNamenode {

    @Test
    public void NameNodeStart() throws Exception {
        String[] args = new String[]{};
        NameNode.main(args);
    }
}
