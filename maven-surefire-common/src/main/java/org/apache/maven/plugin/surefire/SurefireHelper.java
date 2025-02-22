package org.apache.maven.plugin.surefire;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.surefire.log.PluginConsoleLogger;
import org.apache.maven.surefire.api.cli.CommandLineOption;
import org.apache.maven.surefire.api.suite.RunResult;
import org.apache.maven.surefire.api.testset.TestSetFailedException;
import org.apache.maven.surefire.booter.SurefireBooterForkException;

import javax.annotation.Nonnull;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

import static java.util.Collections.unmodifiableList;
import static org.apache.maven.surefire.api.util.internal.DumpFileUtils.newFormattedDateFileName;
import static org.apache.maven.surefire.shared.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.apache.maven.surefire.api.booter.DumpErrorSingleton.DUMPSTREAM_FILE_EXT;
import static org.apache.maven.surefire.api.booter.DumpErrorSingleton.DUMP_FILE_EXT;
import static org.apache.maven.surefire.api.cli.CommandLineOption.LOGGING_LEVEL_DEBUG;
import static org.apache.maven.surefire.api.cli.CommandLineOption.LOGGING_LEVEL_ERROR;
import static org.apache.maven.surefire.api.cli.CommandLineOption.LOGGING_LEVEL_INFO;
import static org.apache.maven.surefire.api.cli.CommandLineOption.LOGGING_LEVEL_WARN;
import static org.apache.maven.surefire.api.cli.CommandLineOption.SHOW_ERRORS;

/**
 * Helper class for surefire plugins
 */
public final class SurefireHelper
{
    private static final String DUMP_FILE_DATE = newFormattedDateFileName();

    public static final String DUMP_FILE_PREFIX = DUMP_FILE_DATE + "-jvmRun";

    public static final String DUMP_FILENAME_FORMATTER = DUMP_FILE_PREFIX + "%d" + DUMP_FILE_EXT;

    public static final String DUMPSTREAM_FILENAME_FORMATTER = DUMP_FILE_PREFIX + "%d" + DUMPSTREAM_FILE_EXT;

    public static final String DUMPSTREAM_FILENAME = DUMP_FILE_DATE + DUMPSTREAM_FILE_EXT;

    public static final String DUMP_FILENAME = DUMP_FILE_DATE + DUMP_FILE_EXT;

    public static final String EVENTS_BINARY_DUMP_FILENAME_FORMATTER = DUMP_FILE_DATE + "-jvmRun%d-events.bin";

    /**
     * The maximum path that does not require long path prefix on Windows.<br>
     * See {@code sun/nio/fs/WindowsPath} in
     * <a href=
     * "http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/7534523b4174/src/windows/classes/sun/nio/fs/WindowsPath.java#l46">
     * OpenJDK</a>
     * and <a href="https://msdn.microsoft.com/en-us/library/aa365247(VS.85).aspx#maxpath">MSDN article</a>.
     * <br>
     * The maximum path is 260 minus 1 (NUL) but for directories it is 260
     * minus 12 minus 1 (to allow for the creation of a 8.3 file in the directory).
     */
    private static final int MAX_PATH_LENGTH_WINDOWS = 247;

    private static final String[] DUMP_FILES_PRINT =
            {
                    "[date]" + DUMP_FILE_EXT,
                    "[date]-jvmRun[N]" + DUMP_FILE_EXT,
                    "[date]" + DUMPSTREAM_FILE_EXT,
                    "[date]-jvmRun[N]" + DUMPSTREAM_FILE_EXT
            };

    /**
     * The placeholder that is replaced by the executing thread's running number. The thread number
     * range starts with 1
     * Deprecated.
     */
    private static final String THREAD_NUMBER_PLACEHOLDER = "${surefire.threadNumber}";

    /**
     * The placeholder that is replaced by the executing fork's running number. The fork number
     * range starts with 1
     */
    private static final String FORK_NUMBER_PLACEHOLDER = "${surefire.forkNumber}";

    /**
     * Do not instantiate.
     */
    private SurefireHelper()
    {
        throw new IllegalAccessError( "Utility class" );
    }

    @Nonnull
    public static String replaceThreadNumberPlaceholders( @Nonnull String argLine, int threadNumber )
    {
        String threadNumberAsString = String.valueOf( threadNumber );
        return argLine.replace( THREAD_NUMBER_PLACEHOLDER, threadNumberAsString )
                .replace( FORK_NUMBER_PLACEHOLDER, threadNumberAsString );
    }

    public static File replaceForkThreadsInPath( File path, int replacement )
    {
        Deque<String> dirs = new LinkedList<>();
        File root = path;
        while ( !root.exists() )
        {
            dirs.addFirst( replaceThreadNumberPlaceholders( root.getName(), replacement ) );
            root = root.getParentFile();
        }
        File replacedPath = root;
        for ( String dir : dirs )
        {
            replacedPath = new File( replacedPath, dir );
        }
        return replacedPath;
    }

    public static String[] getDumpFilesToPrint()
    {
        return DUMP_FILES_PRINT.clone();
    }

    public static void reportExecution( SurefireReportParameters reportParameters, RunResult result,
                                        PluginConsoleLogger log, Exception firstForkException )
        throws MojoFailureException, MojoExecutionException
    {
        boolean isError = firstForkException != null || result.isTimeout() || !result.isErrorFree();
        boolean isTooFlaky = isTooFlaky( result, reportParameters );
        if ( !isError && !isTooFlaky )
        {
            if ( result.getCompletedCount() == 0 && failIfNoTests( reportParameters ) )
            {
                throw new MojoFailureException( "No tests were executed!  "
                                                        + "(Set -DfailIfNoTests=false to ignore this error.)" );
            }
            return;
        }

        if ( reportParameters.isTestFailureIgnore() )
        {
            String errorMessage = createErrorMessage( reportParameters, result, firstForkException );

            if ( firstForkException instanceof SurefireBooterForkException )
            {
                throw new MojoExecutionException( errorMessage, firstForkException );
            }

            log.error( errorMessage );
        }
        else
        {
            throwException( reportParameters, result, firstForkException );
        }
    }

    public static List<CommandLineOption> commandLineOptions( MavenSession session, PluginConsoleLogger log )
    {
        List<CommandLineOption> cli = new ArrayList<>();
        if ( log.isErrorEnabled() )
        {
            cli.add( LOGGING_LEVEL_ERROR );
        }

        if ( log.isWarnEnabled() )
        {
            cli.add( LOGGING_LEVEL_WARN );
        }

        if ( log.isInfoEnabled() )
        {
            cli.add( LOGGING_LEVEL_INFO );
        }

        if ( log.isDebugEnabled() )
        {
            cli.add( LOGGING_LEVEL_DEBUG );
        }

        MavenExecutionRequest request = session.getRequest();

        if ( request.isShowErrors() )
        {
            cli.add( SHOW_ERRORS );
        }

        String failureBehavior = request.getReactorFailureBehavior();
        if ( failureBehavior != null )
        {
            try
            {
                cli.add( CommandLineOption.valueOf( failureBehavior ) );
            }
            catch ( IllegalArgumentException e )
            {
                // CommandLineOption does not have specified enum as string. See getRequest() method in Maven Session.
            }
        }

        return unmodifiableList( cli );
    }

    public static void logDebugOrCliShowErrors( String s, PluginConsoleLogger log, Collection<CommandLineOption> cli )
    {
        if ( cli.contains( LOGGING_LEVEL_DEBUG ) )
        {
            log.debug( s );
        }
        else if ( cli.contains( SHOW_ERRORS ) )
        {
            if ( log.isDebugEnabled() )
            {
                log.debug( s );
            }
            else
            {
                log.info( s );
            }
        }
    }

    /**
     * Escape file path for Windows when the path is too long; otherwise returns {@code path}.
     * <br>
     * See <a href=
     * "http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/7534523b4174/src/windows/classes/sun/nio/fs/WindowsPath.java#l46">
     * sun/nio/fs/WindowsPath</a> for "long path" value explanation (=247), and
     * <a href="https://msdn.microsoft.com/en-us/library/aa365247(VS.85).aspx#maxpath">MSDN article</a>
     * for detailed escaping strategy explanation: in short, {@code \\?\} prefix for path with drive letter
     * or {@code \\?\UNC\} for UNC path.
     *
     * @param path    source path
     * @return escaped to platform path
     */
    public static String escapeToPlatformPath( String path )
    {
        if ( IS_OS_WINDOWS && path.length() > MAX_PATH_LENGTH_WINDOWS )
        {
            path = path.startsWith( "\\\\" ) ? "\\\\?\\UNC\\" + path.substring( 2 ) : "\\\\?\\" + path;
        }
        return path;
    }

    private static boolean failIfNoTests( SurefireReportParameters reportParameters )
    {
        return reportParameters.getFailIfNoTests();
    }

    private static boolean isFatal( Exception firstForkException )
    {
        return firstForkException != null && !( firstForkException instanceof TestSetFailedException );
    }

    private static void throwException( SurefireReportParameters reportParameters, RunResult result,
                                           Exception firstForkException )
            throws MojoFailureException, MojoExecutionException
    {
        if ( isFatal( firstForkException ) || result.isInternalError()  )
        {
            throw new MojoExecutionException( createErrorMessage( reportParameters, result, firstForkException ),
                                                    firstForkException );
        }
        else
        {
            throw new MojoFailureException( createErrorMessage( reportParameters, result, firstForkException ),
                                                  firstForkException );
        }
    }

    private static String createErrorMessage( SurefireReportParameters reportParameters, RunResult result,
                                              Exception firstForkException )
    {
        StringBuilder msg = new StringBuilder( 512 );

        if ( result.isTimeout() )
        {
            msg.append( "There was a timeout in the fork" );
        }
        else
        {
            if ( result.getFailures() > 0 )
            {
                msg.append( "Changed!" );
            }
            if ( isTooFlaky( result, reportParameters ) )
            {
                if ( result.getFailures() > 0 )
                {
                    msg.append( "\n" );
                }
                msg.append( "There" )
                    .append( result.getFlakes() == 1 ? " is " : " are " )
                    .append( result.getFlakes() )
                    .append( result.getFlakes() == 1 ? " flake " : " flakes " )
                    .append( "and failOnFlakeCount is set to " )
                    .append( reportParameters.getFailOnFlakeCount() )
                    .append( "." );
            }
            msg.append( "\n\nPlease refer to " )
                    .append( reportParameters.getReportsDirectory() )
                    .append( " for the individual test results." )
                    .append( '\n' )
                    .append( "Please refer to dump files (if any exist) " )
                    .append( DUMP_FILES_PRINT[0] )
                    .append( ", " )
                    .append( DUMP_FILES_PRINT[1] )
                    .append( " and " )
                    .append( DUMP_FILES_PRINT[2] )
                    .append( "." );
        }

        if ( firstForkException != null && firstForkException.getLocalizedMessage() != null )
        {
            msg.append( '\n' )
                    .append( firstForkException.getLocalizedMessage() );
        }

        if ( result.isFailure() )
        {
            msg.append( '\n' )
                    .append( result.getFailure() );
        }

        return msg.toString();
    }

    private static boolean isTooFlaky( RunResult result, SurefireReportParameters reportParameters )
    {
        int failOnFlakeCount = reportParameters.getFailOnFlakeCount();
        return failOnFlakeCount > 0 && result.getFlakes() >= failOnFlakeCount;
    }

}
