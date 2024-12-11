package filesystem;

import java.io.IOException;
import java.lang.*;
import java.util.ArrayList;
import java.util.List;


public class FileSystem {
    private Disk diskDevice;

    private int iNodeNumber;
    private int fileDescriptor;
    private INode iNodeForFile;

    public FileSystem() throws IOException {
        diskDevice = new Disk();
        diskDevice.format();
    }

    /***
     * Create a file with the name <code>fileName</code>
     *
     * @param fileName - name of the file to create
     * @throws IOException
     */
    public int create(String fileName) throws IOException {
        INode tmpINode = null;

        boolean isCreated = false;

        for (int i = 0; i < Disk.NUM_INODES && !isCreated; i++) {
            tmpINode = diskDevice.readInode(i);
            String name = tmpINode.getFileName();
            if (name.trim().equals(fileName)){
                throw new IOException("FileSystem::create: "+fileName+
                        " already exists");
            } else if (tmpINode.getFileName() == null) {
                this.iNodeForFile = new INode();
                this.iNodeForFile.setFileName(fileName);
                this.iNodeNumber = i;
                this.fileDescriptor = i;
                isCreated = true;
            }
        }
        if (!isCreated) {
            throw new IOException("FileSystem::create: Unable to create file");
        }

        return fileDescriptor;
    }

    /**
     * Removes the file
     *
     * @param fileName
     * @throws IOException
     */
    public void delete(String fileName) throws IOException {
        INode tmpINode = null;
        boolean isFound = false;
        int inodeNumForDeletion = -1;

        /**
         * Find the non-null named inode that matches,
         * If you find it, set its file name to null
         * to indicate it is unused
         */
        for (int i = 0; i < Disk.NUM_INODES && !isFound; i++) {
            tmpINode = diskDevice.readInode(i);

            String fName = tmpINode.getFileName();

            if (fName != null && fName.trim().compareTo(fileName.trim()) == 0) {
                isFound = true;
                inodeNumForDeletion = i;
                break;
            }
        }

        /***
         * If file found, go ahead and deallocate its
         * blocks and null out the filename.
         */
        if (isFound) {
            deallocateBlocksForFile(inodeNumForDeletion);
            tmpINode.setFileName(null);
            diskDevice.writeInode(tmpINode, inodeNumForDeletion);
            this.iNodeForFile = null;
            this.fileDescriptor = -1;
            this.iNodeNumber = -1;
        }
    }


    /***
     * Makes the file available for reading/writing
     *
     * @return
     * @throws IOException
     */
    public int open(String fileName) throws IOException {
        this.fileDescriptor = -1;
        this.iNodeNumber = -1;
        INode tmpINode = null;
        boolean isFound = false;
        int iNodeContainingName = -1;

        for (int i = 0; i < Disk.NUM_INODES && !isFound; i++) {
            tmpINode = diskDevice.readInode(i);
            String fName = tmpINode.getFileName();
            if (fName != null) {
                if (fName.trim().compareTo(fileName.trim()) == 0) {
                    isFound = true;
                    iNodeContainingName = i;
                    this.iNodeForFile = tmpINode;
                }
            }
        }

        if (isFound) {
            this.fileDescriptor = iNodeContainingName;
            this.iNodeNumber = fileDescriptor;
        }

        return this.fileDescriptor;
    }


    /***
     * Closes the file
     *
     * @throws IOException If disk is not accessible for writing
     */
    public void close(int fileDescriptor) throws IOException {
        if (fileDescriptor != this.iNodeNumber){
            throw new IOException("FileSystem::close: file descriptor, "+
                    fileDescriptor + " does not match file descriptor " +
                    "of open file");
        }
        diskDevice.writeInode(this.iNodeForFile, this.iNodeNumber);
        this.iNodeForFile = null;
        this.fileDescriptor = -1;
        this.iNodeNumber = -1;
    }


    /**
     * Reads file and outputs as a String
     * 
     * @return
     * @param fileDescriptor
     * @throws IOException
     */
    public String read(int fileDescriptor) throws IOException {
        if (fileDescriptor != this.iNodeNumber) {
            throw new IOException("FileSystem::read: Invalid file descriptor.");
        }

        INode inode = this.iNodeForFile;
        int fileSize = inode.getSize();
        byte[] fileData = new byte[fileSize];

        int bytesRead = 0;
        for (int i = 0; i < INode.NUM_BLOCK_POINTERS && bytesRead < fileSize; i++) {
            int blockNumber = inode.getBlockPointer(i);
            if (blockNumber == -1) break;

            byte[] blockData = diskDevice.readDataBlock(blockNumber);
            int toRead = Math.min(fileSize - bytesRead, Disk.BLOCK_SIZE);
            System.arraycopy(blockData, 0, fileData, bytesRead, toRead);
            bytesRead += toRead;
        }

        return new String(fileData);
    }


    /**
     * Add your Javadoc documentation for this method
     */
    public void write(int fileDescriptor, String data) throws IOException {
        if (fileDescriptor != this.iNodeNumber) {
            throw new IOException("FileSystem::write: Invalid file descriptor.");
        }

        byte[] dataBytes = data.getBytes();
        int requiredBlocks = (int) Math.ceil((double) dataBytes.length / Disk.BLOCK_SIZE);

        int[] allocatedBlocks = allocateBlocksForFile(this.iNodeNumber, dataBytes.length);
        
        int bytesWritten = 0;
        for (int i = 0; i < allocatedBlocks.length; i++) {
            int blockNumber = allocatedBlocks[i];
            if (blockNumber == -1) break;

            int toWrite = Math.min(dataBytes.length - bytesWritten, Disk.BLOCK_SIZE);
            byte[] blockData = new byte[Disk.BLOCK_SIZE];
            System.arraycopy(dataBytes, bytesWritten, blockData, 0, toWrite);
            diskDevice.writeDataBlock(blockData, blockNumber);
            bytesWritten += toWrite;
        }

        this.iNodeForFile.setSize(dataBytes.length);
        diskDevice.writeInode(this.iNodeForFile, this.iNodeNumber);
    }


    /**
     * Add your Javadoc documentation for this method
     */
    private int[] allocateBlocksForFile(int iNodeNumber, int numBytes) throws IOException {
        int requiredBlocks = (int) Math.ceil((double) numBytes / Disk.BLOCK_SIZE);
        byte[] freeBlockList = diskDevice.readFreeBlockList();
        List<Integer> allocatedBlocks = new ArrayList<>();

        for (int i = 0; i < Disk.NUM_BLOCKS && allocatedBlocks.size() < requiredBlocks; i++) {
            int byteIndex = i / 8;
            int bitIndex = i % 8;

            if ((freeBlockList[byteIndex] & (1 << bitIndex)) == 0) {
                freeBlockList[byteIndex] |= (1 << bitIndex);
                allocatedBlocks.add(i);
            }
        }

        if (allocatedBlocks.size() < requiredBlocks) {
            throw new IOException("FileSystem::allocateBlocksForFile: Not enough free blocks.");
        }

        diskDevice.writeFreeBlockList(freeBlockList);

        INode inode = diskDevice.readInode(iNodeNumber);
        for (int i = 0; i < allocatedBlocks.size(); i++) {
            inode.setBlockPointer(i, allocatedBlocks.get(i));
        }

        diskDevice.writeInode(inode, iNodeNumber);
        return allocatedBlocks.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Add your Javadoc documentation for this method
     */
    private void deallocateBlocksForFile(int iNodeNumber) throws IOException {
        INode inode = diskDevice.readInode(iNodeNumber);
        byte[] freeBlockList = diskDevice.readFreeBlockList();

        for (int i = 0; i < INode.NUM_BLOCK_POINTERS; i++) {
            int blockNumber = inode.getBlockPointer(i);
            if (blockNumber == -1) break;

            int byteIndex = blockNumber / 8;
            int bitIndex = blockNumber % 8;

            freeBlockList[byteIndex] &= ~(1 << bitIndex);
            inode.setBlockPointer(i, -1);
        }

        diskDevice.writeFreeBlockList(freeBlockList);
        diskDevice.writeInode(inode, iNodeNumber);
    }
}
