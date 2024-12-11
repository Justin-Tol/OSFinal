package filesystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class FileSystemTest {

    private FileSystem fileSystem;

    @Before
    public void setUp() throws IOException {
        this.fileSystem = new FileSystem();
    }

    @Test
    public void testRead() throws IOException {
        String fileName = "testFile.txt";
        String content = "This is a test content.";
        int fd = fileSystem.create(fileName);
        fileSystem.write(fd, content);
        fileSystem.close(fd);

        fd = fileSystem.open(fileName);
        String readContent = fileSystem.read(fd);
        fileSystem.close(fd);

        assertEquals(content, readContent);
    }

    @Test
    public void testWrite() throws IOException {
        String fileName = "writeTestFile.txt";
        String content = "Write operation test content.";
        int fd = fileSystem.create(fileName);
        fileSystem.write(fd, content);
        fileSystem.close(fd);

        fd = fileSystem.open(fileName);
        String readContent = fileSystem.read(fd);
        fileSystem.close(fd);

        assertEquals(content, readContent);
    }

    @Test
    public void testAllocateBlocksForFile() throws IOException {
        String fileName = "allocateTestFile.txt";
        String content = "This content tests block allocation functionality.";
        int fd = fileSystem.create(fileName);
        fileSystem.write(fd, content);
        fileSystem.close(fd);

        fd = fileSystem.open(fileName);
        String readContent = fileSystem.read(fd);
        fileSystem.close(fd);

        assertEquals(content, readContent);
    }

    @Test
    public void testDeallocateBlocksForFile() throws IOException {
        String fileName = "deallocateTestFile.txt";
        String content = "This content tests block deallocation functionality.";
        int fd = fileSystem.create(fileName);
        fileSystem.write(fd, content);
        fileSystem.close(fd);

        fileSystem.delete(fileName);

        fd = fileSystem.open(fileName);
        try {
            fileSystem.read(fd);
            fail("Expected IOException for accessing a deleted file.");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Invalid file descriptor"));
        }
    }

    @After
    public void tearDown() {
        this.fileSystem = null;
    }
}
