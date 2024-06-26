#include "scope.h"
#include <format>

Scope::Scope(std::string name, Scope* parent) {
    if (parent == nullptr) {
        this->longname = name;
    } else {
        this->longname = std::format("{}::{}", parent->longname, name);
    }
    this->parent = parent;
    this->scopeCount = 0;
}

std::shared_ptr<Symbol> Scope::find_symbol(const std::string& id) const {
    auto it = symbolTable.find(id);
        if (it != symbolTable.end()) {
            return it->second;
        } else if (parent) {
            return parent->find_symbol(id);
        } else {
            return nullptr;
        }
}

bool Scope::add_symbol(std::shared_ptr<Symbol> symbol) {
    auto inserted = (this->symbolTable.emplace(symbol->ID, symbol)).second;
    return inserted;
}

std::map<std::string, std::shared_ptr<Symbol>> Scope::get_all_symbols_in_scope() const {
    if (parent == nullptr) {
        return symbolTable;
    }
    auto pMap = parent->get_all_symbols_in_scope();
    auto map = symbolTable;
    map.insert(pMap.begin(), pMap.end());
    return map;
}