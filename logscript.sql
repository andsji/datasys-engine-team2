-- CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG, logLevel STRING, className STRING, logMessage STRING);
COPY logs FROM 'testlogs.log';
SELECT * FROM logs WHERE sessionId = 'cd236a28-b5e1-488d-828a-c9e727d309fa';
SELECT * FROM logs WHERE statementNumber = 7;
SELECT * FROM logs WHERE logLevel = 'ERROR';