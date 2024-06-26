#include "scopepopulator.h"
#include <iostream>
#include <format>
#include <vector>
#include <algorithm>
#include <numeric>

// TODO: ========== THIS IS DUPLICATED CODE IN TYPECHECKER.CPP =========
std::string join_param_types(const std::vector<Symbol>& symbols) {
    std::vector<std::string> typeStrings;
    std::transform(symbols.begin(), symbols.end(), std::back_inserter(typeStrings), [](const Symbol& symbol) {
        return type_to_str(symbol.type);
    });

    std::ostringstream oss;
    std::copy(typeStrings.begin(), typeStrings.end(), std::ostream_iterator<std::string>(oss, ","));
    std::string result = oss.str();
    if (!result.empty()) {
        result.pop_back(); // remove the trailing comma
    }
    return result;
}
// ========================================================================

void ScopePopulator::push_scope(const std::string& name) {
    scopeStack.push(std::make_shared<Scope>(name, scopeStack.top().get()));
    scopeTable.add_scope(scopeStack.top());
}

std::any ScopePopulator::visitFunctionDecl(threeboltParser::FunctionDeclContext *ctx) {
    auto paramSymbols = ctx->parameters() ? std::any_cast<std::vector<Symbol>>(visit(ctx->parameters())) : std::vector<Symbol>();
    auto paramTypeNames = join_param_types(paramSymbols);
    
    auto shortname = ctx->ID()->getText();
    shortname = std::format("{}({})", shortname, paramTypeNames);

    // check if symbol already defined in this scope (or any parent scope)
    if (scopeStack.top()->find_symbol(shortname)) {
        errorLogger.logError(std::format("Symbol {} already defined in this or previous scope.", shortname), ctx->getStart()->getLine(), ctx->getStart()->getCharPositionInLine());
    } else {
        auto symbol = std::make_shared<Symbol>(shortname, Type::FN);
        symbol->fnCtx = ctx;
        scopeStack.top()->add_symbol(symbol);
    }

    // create new scope and push it onto the stack
    push_scope(shortname);

    // enter fn
    const auto &in = scopeStack.top();

    // validate params are not already defined
    // TODO: this should probably be done in the visit to params, but this function scope is not defined yet :/
    //          could also get more accurate error column then as well.
    for (auto& symbol : paramSymbols) {
        if (scopeStack.top()->find_symbol(symbol.ID)) {
            errorLogger.logError(std::format("Symbol {} already defined in this or previous scope.", symbol.ID), ctx->parameters()->getStart()->getLine(), ctx->parameters()->getStart()->getCharPositionInLine());
            continue;
        }
        auto symbol2 = std::make_shared<Symbol>(symbol.ID, symbol.type);
        symbol2->fnCtx = ctx;
        scopeStack.top()->add_symbol(symbol2);
    }

    // visit function body
    visit(ctx->block());

    // pop current scope
    scopeStack.pop();
    return ctx;
}

std::any ScopePopulator::visitParameters(threeboltParser::ParametersContext *ctx) {

    std::vector<Symbol> paramSymbols;

    for (auto pctx : ctx->parameter()) {
        auto paramSymbol = std::any_cast<Symbol>(visit(pctx));
        paramSymbols.push_back(paramSymbol);
    }

    return paramSymbols;
}

std::any ScopePopulator::visitParameter(threeboltParser::ParameterContext *ctx) {
    auto type = std::any_cast<Type>(visit(ctx->type()));
    auto id = ctx->ID()->getText();
    auto symbol = Symbol(id, type);
    symbol.paramCtx = ctx;
    return symbol;
}

std::any ScopePopulator::visitType(threeboltParser::TypeContext *ctx) {
    return str_to_type(ctx->getText());
}

std::any ScopePopulator::visitIfStmt(threeboltParser::IfStmtContext *ctx) {
    // if statements create a new scope
    // we don't care about other semantics right now; only resolution
    auto& top = scopeStack.top();
    auto shortname = top->get_longname();

    // if-statement block
    push_scope(std::to_string(top->scopeCount++));
    visit(ctx->block());
    scopeStack.pop();

    // all if-else blocks
    for (auto block : ctx->elseIfStmt()) {
        push_scope(std::to_string(top->scopeCount++));

        visit(block);
        scopeStack.pop();
    }

    // else block
    if (ctx->elseStmt()) {
        push_scope(std::to_string(top->scopeCount++));
        visit(ctx->elseStmt());
        scopeStack.pop();
    }

    return ctx;
}

std::any ScopePopulator::visitVariableDecl(threeboltParser::VariableDeclContext *ctx) {
    auto& top = scopeStack.top();
    auto id = ctx->ID();
    if (top->find_symbol(id->getText())) {
        errorLogger.logError(std::format("Symbol {} already defined in this or previous scope.", id->getText()), ctx->getStart()->getLine(), ctx->getStart()->getCharPositionInLine());
    }
    auto symbol = std::make_shared<Symbol>(ctx->ID()->getText(), Type::UNKNOWN);
    symbol->varCtx = ctx;
    scopeStack.top()->add_symbol(symbol);
    return ctx;
}