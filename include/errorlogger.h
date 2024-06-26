#pragma once

#include <string>
#include <vector>
#include <iostream>

struct Error {
    std::string message;
    int line;
    int column;

    Error(const std::string& message, int line, int column) : message(message), line(line), column(column) {}
    Error(const std::string& message) : message(message), line(-1), column(-1) {}
};

// TODO: have warning system
class ErrorLogger {
public:
    void logError(const std::string& message, int line, int column);
    void logError(const std::string& message);
    void printErrors() const;
    bool hasErrors() const;

private:
    std::vector<Error> errors;
    // std::vector<Error> warnings;
};