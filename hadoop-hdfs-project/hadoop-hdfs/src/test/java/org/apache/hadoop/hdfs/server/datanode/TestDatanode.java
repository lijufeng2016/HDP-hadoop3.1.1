package org.apache.hadoop.hdfs.server.datanode;

import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.junit.Test;

public class TestDatanode {
    @Test
    public void DatanodeStart() throws Exception {
        String[] args = new String[]{};
        DataNode.main(args);
    }
}
